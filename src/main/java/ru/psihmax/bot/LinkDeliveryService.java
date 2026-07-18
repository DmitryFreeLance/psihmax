package ru.psihmax.bot;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.psihmax.bot.max.InlineButton;
import ru.psihmax.bot.max.MaxApiClient;
import ru.psihmax.bot.payment.Tariff;
import ru.psihmax.bot.store.LinkDelivery;
import ru.psihmax.bot.store.LinkTarget;
import ru.psihmax.bot.store.OrderStore;
import ru.psihmax.bot.store.PaymentOrder;
import ru.psihmax.bot.store.ScheduledLink;
import ru.psihmax.bot.store.ScheduledLinkStore;

import java.util.List;

@Service
public class LinkDeliveryService {
    private static final Logger log = LoggerFactory.getLogger(LinkDeliveryService.class);

    private final ScheduledLinkStore scheduledLinkStore;
    private final OrderStore orderStore;
    private final MaxApiClient maxApiClient;

    public LinkDeliveryService(ScheduledLinkStore scheduledLinkStore, OrderStore orderStore, MaxApiClient maxApiClient) {
        this.scheduledLinkStore = scheduledLinkStore;
        this.orderStore = orderStore;
        this.maxApiClient = maxApiClient;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void sendDueLinks() {
        processDueLinks();
    }

    public synchronized void processDueLinks() {
        Instant now = Instant.now();
        for (ScheduledLink link : scheduledLinkStore.activeLinks()) {
            Instant sendAt = Instant.parse(link.sendAt());
            if (sendAt.isAfter(now)) {
                continue;
            }
            deliver(link);
        }
    }

    public synchronized void processDueLinksForUser(long userId) {
        Instant now = Instant.now();
        for (ScheduledLink link : scheduledLinkStore.activeLinks()) {
            Instant sendAt = Instant.parse(link.sendAt());
            if (sendAt.isAfter(now)) {
                continue;
            }
            eligibleTariffFor(link, userId).ifPresent(tariffCode -> send(link, userId, tariffCode));
        }
    }

    private void deliver(ScheduledLink link) {
        Map<Long, String> recipients = new LinkedHashMap<>();
        for (PaymentOrder order : orderStore.paidOrders()) {
            eligibleTariffFor(link, order.userId()).ifPresent(tariffCode -> recipients.putIfAbsent(order.userId(), tariffCode));
        }
        recipients.forEach((userId, tariffCode) -> send(link, userId, tariffCode));
    }

    private Optional<String> eligibleTariffFor(ScheduledLink link, long userId) {
        boolean alreadySent = link.deliveries().stream().anyMatch(delivery -> delivery.userId() == userId);
        if (alreadySent) {
            return Optional.empty();
        }

        if (link.target() == LinkTarget.COURSE) {
            if (orderStore.paidCount(userId, Tariff.FULL.code()) > 0) {
                return Optional.of(Tariff.FULL.code());
            }
            if (hasUnusedPayment(LinkTarget.COURSE, userId, Tariff.WEEKLY.code())) {
                return Optional.of(Tariff.WEEKLY.code());
            }
            if (hasUnusedPayment(LinkTarget.COURSE, userId, Tariff.REPEAT.code())) {
                return Optional.of(Tariff.REPEAT.code());
            }
        }

        if (link.target() == LinkTarget.SESSION && hasUnusedPayment(LinkTarget.SESSION, userId, Tariff.SESSION.code())) {
            return Optional.of(Tariff.SESSION.code());
        }

        return Optional.empty();
    }

    private boolean hasUnusedPayment(LinkTarget target, long userId, String tariffCode) {
        long paid = orderStore.paidCount(userId, tariffCode);
        long delivered = scheduledLinkStore.deliveredCount(target, userId, tariffCode);
        return paid > delivered;
    }

    private void send(ScheduledLink link, long userId, String tariffCode) {
        boolean alreadySent = scheduledLinkStore.find(link.id())
                .stream()
                .flatMap(item -> item.deliveries().stream())
                .anyMatch(delivery -> delivery.userId() == userId);
        if (alreadySent) {
            return;
        }

        try {
            String text = link.target() == LinkTarget.COURSE
                    ? "✨ *Ссылка для обучения*\n\n" + link.title()
                    : "🌀 *Ссылка на личную сессию*\n\n" + link.title();
            maxApiClient.sendMessageToUser(userId, text, List.of(
                    List.of(InlineButton.link("🔗 Открыть ссылку", link.url())),
                    List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
            ));
            scheduledLinkStore.addDelivery(link.id(), new LinkDelivery(userId, tariffCode, Instant.now().toString()));
            log.info("Scheduled link {} delivered to user {} by tariff {}", link.id(), userId, tariffCode);
        } catch (Exception e) {
            log.warn("Cannot deliver scheduled link {} to user {}", link.id(), userId, e);
        }
    }
}
