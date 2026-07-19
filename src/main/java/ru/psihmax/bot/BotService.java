package ru.psihmax.bot;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.psihmax.bot.config.BotProperties;
import ru.psihmax.bot.max.InlineButton;
import ru.psihmax.bot.max.MaxApiClient;
import ru.psihmax.bot.payment.CreatedPayment;
import ru.psihmax.bot.payment.PaymentRequest;
import ru.psihmax.bot.payment.Tariff;
import ru.psihmax.bot.payment.YooKassaClient;
import ru.psihmax.bot.store.AdminStore;
import ru.psihmax.bot.store.LinkTarget;
import ru.psihmax.bot.store.OrderStore;
import ru.psihmax.bot.store.PaymentOrder;
import ru.psihmax.bot.store.ScheduledLink;
import ru.psihmax.bot.store.ScheduledLinkStore;
import ru.psihmax.bot.store.UserProfile;

@Service
public class BotService {
    private static final Logger log = LoggerFactory.getLogger(BotService.class);
    private static final DateTimeFormatter ADMIN_DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final MaxApiClient maxApiClient;
    private final YooKassaClient yooKassaClient;
    private final OrderStore orderStore;
    private final AdminStore adminStore;
    private final ReviewService reviewService;
    private final ScheduledLinkStore scheduledLinkStore;
    private final LinkDeliveryService linkDeliveryService;
    private final BotProperties properties;
    private final Map<Long, UserState> states = new ConcurrentHashMap<>();
    private final Map<Long, AdminLinkDraft> adminLinkDrafts = new ConcurrentHashMap<>();

    public BotService(MaxApiClient maxApiClient, YooKassaClient yooKassaClient, OrderStore orderStore, AdminStore adminStore,
                      ReviewService reviewService, ScheduledLinkStore scheduledLinkStore, LinkDeliveryService linkDeliveryService,
                      BotProperties properties) {
        this.maxApiClient = maxApiClient;
        this.yooKassaClient = yooKassaClient;
        this.orderStore = orderStore;
        this.adminStore = adminStore;
        this.reviewService = reviewService;
        this.scheduledLinkStore = scheduledLinkStore;
        this.linkDeliveryService = linkDeliveryService;
        this.properties = properties;
    }

    public void handleUpdate(JsonNode update) {
        Incoming incoming = parse(update);
        if (incoming.userId() == null) {
            log.debug("Skip update without user id: {}", update);
            return;
        }
        adminStore.rememberUser(incoming.userId(), incoming.userDisplayName());

        try {
            if (incoming.payload() != null && !incoming.payload().isBlank()) {
                handlePayload(incoming);
                return;
            }
            handleText(incoming);
        } catch (Exception e) {
            log.warn("Cannot handle update {}", update, e);
            sendTo(incoming, "Не получилось выполнить действие. Попробуйте ещё раз или вернитесь в меню.", mainMenu(incoming.userId()));
        }
    }

    public void notifyPaymentSucceeded(PaymentOrder order) {
        sendToUser(order.userId(), """
                ✨ Оплата прошла успешно!

                Спасибо, %s. Вы оплатили: *%s*.

                Я свяжусь с вами, если нужно согласовать детали.
                """.formatted(order.customerName(), order.tariffTitle()), mainMenu(order.userId()));

        for (Long adminId : adminStore.adminIds()) {
            sendToUser(adminId, """
                    ✅ *Новая оплата*

                    💎 Тариф: *%s*
                    💰 Сумма: *%s руб.*
                    👤 Имя: *%s*
                    📞 Телефон: `%s`
                    📧 Email для чека: `%s`
                    🆔 MAX user id: `%d`
                    👁 Профиль: %s
                    🧾 YooKassa payment id: `%s`
                    """.formatted(
                            order.tariffTitle(),
                            order.amountValue(),
                            order.customerName(),
                            order.phone(),
                            blankToDash(order.email()),
                            order.userId(),
                            blankToDash(order.userDisplayName()),
                            order.paymentId()
                    ), List.of(List.of(InlineButton.callback("🏠 Меню бота", "MAIN"))));
        }
        linkDeliveryService.processDueLinksForUser(order.userId());
    }

    private void handleText(Incoming incoming) {
        String text = incoming.text() == null ? "" : incoming.text().trim();
        UserState state = states.get(incoming.userId());

        if ("/start".equalsIgnoreCase(text) || "start".equalsIgnoreCase(text)) {
            states.remove(incoming.userId());
            adminLinkDrafts.remove(incoming.userId());
            sendStart(incoming);
            return;
        }

        if ("/admin".equalsIgnoreCase(text)) {
            states.remove(incoming.userId());
            adminLinkDrafts.remove(incoming.userId());
            sendAdminPanel(incoming);
            return;
        }

        String buttonPayload = payloadFromButtonText(text);
        if (buttonPayload != null) {
            handlePayload(new Incoming(incoming.userId(), incoming.chatId(), incoming.text(), incoming.callbackId(), buttonPayload, incoming.userDisplayName()));
            return;
        }

        AdminLinkDraft draft = adminLinkDrafts.get(incoming.userId());
        if (draft != null) {
            handleAdminLinkText(incoming, draft, text);
            return;
        }

        if (state != null && state.step() == Step.WAITING_NAME) {
            if (text.length() < 2) {
                sendTo(incoming, "Напишите, пожалуйста, имя чуть подробнее — обычным сообщением в этот чат.", backToTariffs());
                return;
            }
            states.put(incoming.userId(), state.withName(text));
            sendTo(incoming, """
                    *Шаг 2 из 3*

                    Спасибо, %s. Теперь отправьте *номер телефона* для связи.

                    Можно в любом удобном формате, например: `+7 999 123-45-67`.
                    """.formatted(text), backToTariffs());
            return;
        }

        if (state != null && state.step() == Step.WAITING_PHONE) {
            Optional<String> normalizedPhone = normalizePhone(text);
            if (normalizedPhone.isEmpty()) {
                sendTo(incoming, """
                        Похоже, номер телефона написан не полностью.

                        Отправьте, пожалуйста, номер обычным сообщением. Например: `+7 999 123-45-67`.
                        """, backToTariffs());
                return;
            }
            states.put(incoming.userId(), state.withPhone(normalizedPhone.get()));
            sendTo(incoming, """
                    *Шаг 3 из 3*

                    Теперь отправьте *email для чека*.

                    На него ЮKassa отправит ссылку на зарегистрированный чек.
                    """, backToTariffs());
            return;
        }

        if (state != null && state.step() == Step.WAITING_EMAIL) {
            if (!isValidEmail(text)) {
                sendTo(incoming, """
                        Email выглядит некорректно.

                        Отправьте, пожалуйста, email обычным сообщением. Например: `name@example.com`.
                        """, backToTariffs());
                return;
            }
            createPayment(incoming, state.withEmail(text));
            return;
        }

        sendTo(incoming, "Я рядом и отвечаю через кнопки ниже. Выберите нужный раздел.", mainMenu(incoming.userId()));
    }

    private void handlePayload(Incoming incoming) {
        String payload = incoming.payload();
        if ("MAIN".equals(payload)) {
            states.remove(incoming.userId());
            sendStart(incoming);
            return;
        }
        if ("ABOUT".equals(payload)) {
            sendTo(incoming, aboutText(), rows(
                    List.of(InlineButton.callback("✨ Курс", "COURSE"), InlineButton.callback("💳 Тарифы", "TARIFFS")),
                    List.of(InlineButton.callback("📞 Связь", "CONTACTS"), InlineButton.callback("🏠 Главное меню", "MAIN"))
            ));
            return;
        }
        if ("COURSE".equals(payload)) {
            sendCourse(incoming);
            return;
        }
        if ("TARIFFS".equals(payload)) {
            states.remove(incoming.userId());
            sendTariffs(incoming);
            return;
        }
        if ("SESSION_INFO".equals(payload)) {
            sendTo(incoming, sessionText(), rows(
                    List.of(InlineButton.callback("🌀 Записаться на сессию", buyPayload(Tariff.SESSION))),
                    List.of(InlineButton.callback("💳 Все тарифы", "TARIFFS")),
                    List.of(InlineButton.callback("↩️ Назад", "MAIN"))
            ));
            return;
        }
        if ("RULES".equals(payload)) {
            sendTo(incoming, rulesText(), rows(
                    List.of(InlineButton.callback("💳 Выбрать оплату", "TARIFFS")),
                    List.of(InlineButton.callback("↩️ Назад к курсу", "COURSE"), InlineButton.callback("🏠 Меню", "MAIN"))
            ));
            return;
        }
        if ("CONTACTS".equals(payload) || "LINKS".equals(payload)) {
            sendTo(incoming, contactsText(), rows(
                    List.of(InlineButton.link("💙 VK", properties.links().vkUrl()), InlineButton.link("✈️ Telegram", properties.links().telegramUrl())),
                    List.of(InlineButton.callback("↩️ Назад", "MAIN"))
            ));
            return;
        }
        if ("REVIEWS".equals(payload)) {
            sendReviewsMenu(incoming);
            return;
        }
        if (payload.startsWith("REVIEWS:")) {
            sendPhotoReviewPage(incoming, parsePage(payload.substring("REVIEWS:".length())));
            return;
        }
        if ("PHOTO_REVIEWS".equals(payload)) {
            sendPhotoReviewPage(incoming, 0);
            return;
        }
        if (payload.startsWith("PHOTO_REVIEWS:")) {
            sendPhotoReviewPage(incoming, parsePage(payload.substring("PHOTO_REVIEWS:".length())));
            return;
        }
        if ("VIDEO_REVIEWS".equals(payload)) {
            sendVideoReviewPage(incoming, 0);
            return;
        }
        if (payload.startsWith("VIDEO_REVIEWS:")) {
            sendVideoReviewPage(incoming, parsePage(payload.substring("VIDEO_REVIEWS:".length())));
            return;
        }
        if ("ADMIN_MENU".equals(payload)) {
            sendAdminMenu(incoming);
            return;
        }
        if ("ADMIN_PANEL".equals(payload)) {
            sendAdminPanel(incoming);
            return;
        }
        if ("LINK_ADD_COURSE".equals(payload)) {
            startLinkDraft(incoming, LinkTarget.COURSE);
            return;
        }
        if ("LINK_ADD_SESSION".equals(payload)) {
            startLinkDraft(incoming, LinkTarget.SESSION);
            return;
        }
        if ("LINK_LIST".equals(payload)) {
            sendScheduledLinks(incoming);
            return;
        }
        if (payload.startsWith("LINK_CANCEL:")) {
            cancelScheduledLink(incoming, payload.substring("LINK_CANCEL:".length()));
            return;
        }
        if (payload.startsWith("ADMIN_ADD:")) {
            addAdminByPayload(incoming, payload);
            return;
        }
        if (payload.startsWith("BUY:")) {
            Tariff.byCode(payload.substring("BUY:".length())).ifPresentOrElse(
                    tariff -> startPaymentDialog(incoming, tariff),
                    () -> sendTariffs(incoming)
            );
            return;
        }

        sendStart(incoming);
    }

    private void startPaymentDialog(Incoming incoming, Tariff tariff) {
        states.put(incoming.userId(), new UserState(Step.WAITING_NAME, tariff, null, null, null));
        sendTo(incoming, """
                *Шаг 1 из 3*

                Вы выбрали: *%s*
                Стоимость: *%s*

                Чтобы я сформировала ссылку на оплату, ответьте обычным сообщением в этот чат:

                *Как вас зовут?*
                """.formatted(tariff.title(), tariff.amountText()), backToTariffs());
    }

    private void createPayment(Incoming incoming, UserState state) {
        PaymentRequest request = new PaymentRequest(
                incoming.userId(),
                incoming.chatId(),
                incoming.userDisplayName(),
                state.name(),
                state.phone(),
                state.email(),
                state.tariff()
        );
        CreatedPayment payment = yooKassaClient.createPayment(request);

        PaymentOrder order = new PaymentOrder(
                payment.paymentId(),
                incoming.userId(),
                incoming.chatId(),
                incoming.userDisplayName(),
                state.name(),
                state.phone(),
                state.email(),
                state.tariff().code(),
                state.tariff().title(),
                state.tariff().amount().setScale(2).toPlainString(),
                payment.confirmationUrl(),
                "PENDING",
                Instant.now().toString(),
                null
        );
        orderStore.save(order);
        states.remove(incoming.userId());

        sendTo(incoming, """
                %s, я сформировала ссылку на оплату.

                Тариф: *%s*
                Сумма: *%s*
                """.formatted(state.name(), state.tariff().title(), state.tariff().amountText()), rows(
                List.of(InlineButton.link("💳 Оплатить", payment.confirmationUrl())),
                List.of(InlineButton.callback("💎 Выбрать другой тариф", "TARIFFS")),
                List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
        ));
    }

    private void sendStart(Incoming incoming) {
        sendTo(incoming, startText(), mainMenu(incoming.userId()));
    }

    private void sendTariffs(Incoming incoming) {
        sendTo(incoming, tariffsText(), rows(
                List.of(InlineButton.callback("🔥 Полный курс 35 000 ₽", buyPayload(Tariff.FULL))),
                List.of(InlineButton.callback("💸 Неделя 3 500 ₽", buyPayload(Tariff.WEEKLY))),
                List.of(InlineButton.callback("🌀 Личная сессия 15 000 ₽", buyPayload(Tariff.SESSION))),
                List.of(InlineButton.callback("🔁 Повторное обучение 1 000 ₽/нед.", buyPayload(Tariff.REPEAT))),
                List.of(InlineButton.callback("⚠️ Правила", "RULES"), InlineButton.callback("↩️ Назад", "MAIN"))
        ));
    }

    private void sendCourse(Incoming incoming) {
        List<List<InlineButton>> buttons = rows(
                List.of(InlineButton.callback("💳 Выбрать формат", "TARIFFS")),
                List.of(InlineButton.callback("⚠️ Правила пространства", "RULES")),
                List.of(InlineButton.callback("↩️ Назад", "MAIN"))
        );
        reviewService.courseVideoToken().ifPresentOrElse(
                token -> sendVideoTo(incoming, courseText(), token, buttons),
                () -> sendTo(incoming, courseText(), buttons)
        );
    }

    private void sendReviewsMenu(Incoming incoming) {
        sendTo(incoming, """
                💬 *Отзывы*

                Выберите, какие отзывы хотите посмотреть.
                """, rows(
                List.of(InlineButton.callback("🖼 Фото отзывы", "PHOTO_REVIEWS")),
                List.of(InlineButton.callback("🎬 Видео отзывы", "VIDEO_REVIEWS")),
                List.of(InlineButton.callback("↩️ Назад", "MAIN"))
        ));
    }

    private void sendPhotoReviewPage(Incoming incoming, int page) {
        reviewService.getPhotoPage(page).ifPresentOrElse(review -> {
            int current = review.page() + 1;
            int previous = Math.max(0, review.page() - 1);
            int next = Math.min(review.totalPages() - 1, review.page() + 1);

            List<List<InlineButton>> buttons = rows(
                    List.of(
                            InlineButton.callback("🖼⬅️ " + (previous + 1) + "/" + review.totalPages(), "PHOTO_REVIEWS:" + previous),
                            InlineButton.callback("🖼 " + current + "/" + review.totalPages(), "PHOTO_REVIEWS:" + review.page()),
                            InlineButton.callback("🖼 " + (next + 1) + "/" + review.totalPages() + " ➡️", "PHOTO_REVIEWS:" + next)
                    ),
                    List.of(InlineButton.callback("🎬 Видео отзывы", "VIDEO_REVIEWS"), InlineButton.callback("💳 Тарифы", "TARIFFS")),
                    List.of(InlineButton.callback("🏠 Меню", "MAIN"))
            );
            sendImagesTo(incoming, "🖼 *Фото отзывы*\nФото " + review.from() + "-" + review.to() + " из " + review.totalPhotos(), review.tokens(), buttons);
        }, () -> sendTo(incoming, """
                🖼 Фото отзывы пока не найдены.

                Проверьте, что папка `otzivi` существует и в ней есть фото.
                """, mainMenu(incoming.userId())));
    }

    private void sendVideoReviewPage(Incoming incoming, int page) {
        reviewService.getVideoPage(page).ifPresentOrElse(review -> {
            int current = review.page() + 1;
            int previous = Math.max(0, review.page() - 1);
            int next = Math.min(review.total() - 1, review.page() + 1);

            List<List<InlineButton>> buttons = rows(
                    List.of(
                            InlineButton.callback("🎬⬅️ " + (previous + 1) + "/" + review.total(), "VIDEO_REVIEWS:" + previous),
                            InlineButton.callback("🎬 " + current + "/" + review.total(), "VIDEO_REVIEWS:" + review.page()),
                            InlineButton.callback("🎬 " + (next + 1) + "/" + review.total() + " ➡️", "VIDEO_REVIEWS:" + next)
                    ),
                    List.of(InlineButton.callback("🖼 Фото отзывы", "PHOTO_REVIEWS"), InlineButton.callback("💳 Тарифы", "TARIFFS")),
                    List.of(InlineButton.callback("🏠 Меню", "MAIN"))
            );
            sendVideoTo(incoming, "🎬 *Видео отзывы*\nВидео " + current + " из " + review.total(), review.token(), buttons);
        }, () -> sendTo(incoming, """
                🎬 Видео отзывы пока не найдены.

                Проверьте, что в папке `videos` есть файлы `1.mp4`, `2.mp4`, `3.mp4`.
                """, mainMenu(incoming.userId())));
    }

    private void sendAdminMenu(Incoming incoming) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Команда `/admin` доступна только администраторам.", mainMenu(incoming.userId()));
            return;
        }

        List<UserProfile> users = adminStore.knownUsers();
        if (users.isEmpty()) {
            sendTo(incoming, "Пока нет пользователей, которых можно добавить в админы.", mainMenu(incoming.userId()));
            return;
        }

        List<List<InlineButton>> buttons = new ArrayList<>();
        users.stream().limit(30).forEach(user -> {
            String marker = adminStore.isAdmin(user.userId()) ? " ✅" : "";
            buttons.add(List.of(InlineButton.callback("👤 " + shortLabel(user.displayName(), user.userId()) + " (" + user.userId() + ")" + marker, "ADMIN_ADD:" + user.userId())));
        });
        buttons.add(List.of(InlineButton.callback("🏠 Главное меню", "MAIN")));

        sendTo(incoming, """
                🛠 *Админы бота*

                Выберите пользователя из тех, кто уже писал боту. После нажатия он начнёт получать уведомления об оплатах.
                """, buttons);
    }

    private void addAdminByPayload(Incoming incoming, String payload) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Команда доступна только администраторам.", mainMenu(incoming.userId()));
            return;
        }

        try {
            long userId = Long.parseLong(payload.substring("ADMIN_ADD:".length()));
            adminStore.addAdmin(userId);
            String label = adminStore.findUser(userId)
                    .map(user -> shortLabel(user.displayName(), user.userId()))
                    .orElse("Пользователь " + userId);
            sendTo(incoming, "✅ " + label + " добавлен в админы.", rows(
                    List.of(InlineButton.callback("🛠 Назад к админам", "ADMIN_MENU")),
                    List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
            ));
            sendToUser(userId, "✅ Я выдала вам права администратора в моём боте.", mainMenu(userId));
        } catch (NumberFormatException e) {
            sendAdminMenu(incoming);
        }
    }

    private void sendAdminPanel(Incoming incoming) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Админ-панель доступна только администраторам.", mainMenu(incoming.userId()));
            return;
        }
        sendTo(incoming, """
                🛠 *Админ-панель*

                Здесь можно управлять администраторами и расписанием ссылок для оплативших клиентов.
                """, rows(
                List.of(InlineButton.callback("➕ Ссылка курса", "LINK_ADD_COURSE")),
                List.of(InlineButton.callback("➕ Ссылка личной сессии", "LINK_ADD_SESSION")),
                List.of(InlineButton.callback("📋 Запланированные ссылки", "LINK_LIST")),
                List.of(InlineButton.callback("👥 Добавить админа", "ADMIN_MENU")),
                List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
        ));
    }

    private void startLinkDraft(Incoming incoming, LinkTarget target) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Это действие доступно только администраторам.", mainMenu(incoming.userId()));
            return;
        }
        adminLinkDrafts.put(incoming.userId(), new AdminLinkDraft(target, AdminLinkStep.WAITING_URL, null));
        String audience = target == LinkTarget.COURSE
                ? "полный курс + оплаченная понедельная/повторная неделя"
                : "оплаченные личные сессии";
        sendTo(incoming, """
                🔗 *Новая ссылка*

                Аудитория: *%s*

                Отправьте саму ссылку обычным сообщением.
                """.formatted(audience), adminBackButtons());
    }

    private void handleAdminLinkText(Incoming incoming, AdminLinkDraft draft, String text) {
        if (!adminStore.isAdmin(incoming.userId())) {
            adminLinkDrafts.remove(incoming.userId());
            sendTo(incoming, "Это действие доступно только администраторам.", mainMenu(incoming.userId()));
            return;
        }

        if (draft.step() == AdminLinkStep.WAITING_URL) {
            if (!isUrl(text)) {
                sendTo(incoming, "Ссылка выглядит некорректно. Отправьте URL, который начинается с `http://` или `https://`.", adminBackButtons());
                return;
            }
            adminLinkDrafts.put(incoming.userId(), new AdminLinkDraft(draft.target(), AdminLinkStep.WAITING_DATE_TIME, text));
            sendTo(incoming, """
                    🕘 Теперь отправьте дату и время, когда ссылка должна прийти.

                    Формат: `дд.мм.гггг чч:мм`
                    Пример: `25.07.2026 09:00`

                    Часовой пояс: *%s*
                    """.formatted(properties.timeZone()), adminBackButtons());
            return;
        }

        if (draft.step() == AdminLinkStep.WAITING_DATE_TIME) {
            Optional<Instant> sendAt = parseAdminDateTime(text);
            if (sendAt.isEmpty()) {
                sendTo(incoming, "Не смог распознать дату. Используйте формат `дд.мм.гггг чч:мм`, например `25.07.2026 09:00`.", adminBackButtons());
                return;
            }
            String title = draft.target() == LinkTarget.COURSE
                    ? "Материал для обучения"
                    : "Ссылка для личной сессии";
            ScheduledLink link = scheduledLinkStore.create(draft.target(), title, draft.url(), sendAt.get().toString());
            adminLinkDrafts.remove(incoming.userId());
            linkDeliveryService.processDueLinks();
            sendTo(incoming, """
                    ✅ Ссылка сохранена

                    Тип: *%s*
                    Отправка: *%s*
                    URL: %s
                    ID: `%s`
                    """.formatted(linkTargetText(link.target()), formatAdminDateTime(link.sendAt()), link.url(), shortId(link.id())), rows(
                    List.of(InlineButton.callback("📋 Запланированные ссылки", "LINK_LIST")),
                    List.of(InlineButton.callback("🛠 Админ-панель", "ADMIN_PANEL")),
                    List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
            ));
        }
    }

    private void sendScheduledLinks(Incoming incoming) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Это действие доступно только администраторам.", mainMenu(incoming.userId()));
            return;
        }

        List<ScheduledLink> links = scheduledLinkStore.activeLinks();
        if (links.isEmpty()) {
            sendTo(incoming, "Пока нет активных запланированных ссылок.", adminBackButtons());
            return;
        }

        StringBuilder text = new StringBuilder("📋 *Запланированные ссылки*\n\n");
        List<List<InlineButton>> buttons = new ArrayList<>();
        links.stream().limit(12).forEach(link -> {
            String id = shortId(link.id());
            text.append("`").append(id).append("` ")
                    .append(linkTargetText(link.target()))
                    .append("\n")
                    .append(formatAdminDateTime(link.sendAt()))
                    .append("\n")
                    .append(link.url())
                    .append("\n\n");
            buttons.add(List.of(InlineButton.callback("🗑 Удалить " + id, "LINK_CANCEL:" + id)));
        });
        buttons.add(List.of(InlineButton.callback("➕ Ссылка курса", "LINK_ADD_COURSE")));
        buttons.add(List.of(InlineButton.callback("➕ Ссылка личной сессии", "LINK_ADD_SESSION")));
        buttons.add(List.of(InlineButton.callback("🛠 Админ-панель", "ADMIN_PANEL")));
        buttons.add(List.of(InlineButton.callback("🏠 Главное меню", "MAIN")));
        sendTo(incoming, text.toString(), buttons);
    }

    private void cancelScheduledLink(Incoming incoming, String idOrPrefix) {
        if (!adminStore.isAdmin(incoming.userId())) {
            sendTo(incoming, "Это действие доступно только администраторам.", mainMenu(incoming.userId()));
            return;
        }
        Optional<ScheduledLink> link = scheduledLinkStore.activeLinks().stream()
                .filter(item -> item.id().startsWith(idOrPrefix))
                .findFirst();
        if (link.isPresent() && scheduledLinkStore.cancel(link.get().id())) {
            sendTo(incoming, "🗑 Ссылка `" + shortId(link.get().id()) + "` удалена из расписания.", rows(
                    List.of(InlineButton.callback("📋 Запланированные ссылки", "LINK_LIST")),
                    List.of(InlineButton.callback("🛠 Админ-панель", "ADMIN_PANEL")),
                    List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
            ));
        } else {
            sendTo(incoming, "Не нашла активную ссылку с таким ID.", adminBackButtons());
        }
    }

    private void sendTo(Incoming incoming, String text, List<List<InlineButton>> buttons) {
        if (incoming.chatId() != null) {
            maxApiClient.sendMessageToChat(incoming.chatId(), text, buttons);
        } else {
            maxApiClient.sendMessageToUser(incoming.userId(), text, buttons);
        }
    }

    private void sendImageTo(Incoming incoming, String text, String imageToken, List<List<InlineButton>> buttons) {
        if (incoming.chatId() != null) {
            maxApiClient.sendImageToChat(incoming.chatId(), text, imageToken, buttons);
        } else {
            maxApiClient.sendImageToUser(incoming.userId(), text, imageToken, buttons);
        }
    }

    private void sendImagesTo(Incoming incoming, String text, List<String> imageTokens, List<List<InlineButton>> buttons) {
        if (incoming.chatId() != null) {
            maxApiClient.sendImagesToChat(incoming.chatId(), text, imageTokens, buttons);
        } else {
            maxApiClient.sendImagesToUser(incoming.userId(), text, imageTokens, buttons);
        }
    }

    private void sendVideoTo(Incoming incoming, String text, String videoToken, List<List<InlineButton>> buttons) {
        if (incoming.chatId() != null) {
            maxApiClient.sendVideoToChat(incoming.chatId(), text, videoToken, buttons);
        } else {
            maxApiClient.sendVideoToUser(incoming.userId(), text, videoToken, buttons);
        }
    }

    private void sendToUser(long userId, String text, List<List<InlineButton>> buttons) {
        maxApiClient.sendMessageToUser(userId, text, buttons);
    }

    private List<List<InlineButton>> mainMenu(Long userId) {
        List<List<InlineButton>> buttons = new ArrayList<>(List.of(
                List.of(InlineButton.callback("🌌 Обо мне", "ABOUT"), InlineButton.callback("✨ Курс", "COURSE")),
                List.of(InlineButton.callback("💬 Отзывы", "REVIEWS"), InlineButton.callback("💳 Тарифы", "TARIFFS")),
                List.of(InlineButton.callback("🌀 Личная сессия", "SESSION_INFO")),
                List.of(InlineButton.link("📘 Заказать книгу", properties.links().bookUrl())),
                List.of(InlineButton.callback("⚠️ Правила", "RULES"), InlineButton.callback("📞 Связь", "CONTACTS"))
        ));
        if (userId != null && adminStore.isAdmin(userId)) {
            buttons.add(List.of(InlineButton.callback("🛠 Админ-панель", "ADMIN_PANEL")));
        }
        return buttons;
    }

    private List<List<InlineButton>> adminBackButtons() {
        return rows(
                List.of(InlineButton.callback("🛠 Админ-панель", "ADMIN_PANEL")),
                List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
        );
    }

    private List<List<InlineButton>> backToTariffs() {
        return rows(
                List.of(InlineButton.callback("💳 К тарифам", "TARIFFS")),
                List.of(InlineButton.callback("🏠 Главное меню", "MAIN"))
        );
    }

    @SafeVarargs
    private final List<List<InlineButton>> rows(List<InlineButton>... rows) {
        return List.of(rows);
    }

    private static String buyPayload(Tariff tariff) {
        return "BUY:" + tariff.code();
    }

    private String payloadFromButtonText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        return switch (text.trim()) {
            case "🏠 Главное меню", "🏠 Меню", "🏠 Меню бота", "↩️ Назад" -> "MAIN";
            case "🌌 Обо мне", "🌌 О практике" -> "ABOUT";
            case "✨ Курс", "↩️ Назад к курсу" -> "COURSE";
            case "💳 Тарифы", "💳 Тарифы и оплата", "💳 Все тарифы", "💳 К тарифам",
                    "💳 Выбрать формат", "💳 Выбрать оплату", "💎 Выбрать другой тариф" -> "TARIFFS";
            case "🌀 Личная сессия" -> "SESSION_INFO";
            case "⚠️ Правила", "⚠️ Правила пространства" -> "RULES";
            case "📞 Связь", "🔗 Ссылки" -> "CONTACTS";
            case "💬 Отзывы" -> "REVIEWS";
            case "🖼 Фото отзывы" -> "PHOTO_REVIEWS";
            case "🎬 Видео отзывы" -> "VIDEO_REVIEWS";
            case "🔥 Полный курс 35 000 ₽" -> buyPayload(Tariff.FULL);
            case "💸 Неделя 3 500 ₽" -> buyPayload(Tariff.WEEKLY);
            case "🌀 Личная сессия 15 000 ₽", "🌀 Записаться на сессию" -> buyPayload(Tariff.SESSION);
            case "🔁 Повторное обучение 1 000 ₽", "🔁 Повторное обучение 1 000 ₽/нед." -> buyPayload(Tariff.REPEAT);
            case "🛠 Назад к админам" -> "ADMIN_MENU";
            case "🛠 Админ-панель" -> "ADMIN_PANEL";
            case "➕ Ссылка курса" -> "LINK_ADD_COURSE";
            case "➕ Ссылка личной сессии" -> "LINK_ADD_SESSION";
            case "📋 Запланированные ссылки" -> "LINK_LIST";
            default -> payloadFromDynamicButtonText(text.trim());
        };
    }

    private String payloadFromDynamicButtonText(String text) {
        if (text.matches(".*\\d+/\\d+.*")) {
            String pageText = text.replaceAll("^.*?(\\d+)/(\\d+).*$", "$1");
            String prefix = text.contains("🎬") ? "VIDEO_REVIEWS:" : "PHOTO_REVIEWS:";
            return prefix + Math.max(0, parsePage(pageText) - 1);
        }
        if (text.startsWith("👤 ")) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\((\\d+)\\)").matcher(text);
            if (matcher.find()) {
                return "ADMIN_ADD:" + matcher.group(1);
            }
        }
        if (text.startsWith("🗑 Удалить ")) {
            return "LINK_CANCEL:" + text.substring("🗑 Удалить ".length()).trim();
        }
        return null;
    }

    private int parsePage(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Optional<String> normalizePhone(String phone) {
        String digits = phone == null ? "" : phone.replaceAll("\\D", "");
        if (digits.length() == 11 && digits.startsWith("8")) {
            return Optional.of("7" + digits.substring(1));
        }
        if (digits.length() == 10) {
            return Optional.of("7" + digits);
        }
        if (digits.length() >= 11 && digits.length() <= 15) {
            return Optional.of(digits);
        }
        return Optional.empty();
    }

    private boolean isValidEmail(String email) {
        return email != null && email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    }

    private boolean isUrl(String url) {
        return url != null && url.matches("^https?://\\S+$");
    }

    private Optional<Instant> parseAdminDateTime(String value) {
        try {
            LocalDateTime dateTime = LocalDateTime.parse(value.trim(), ADMIN_DATE_TIME);
            return Optional.of(dateTime.atZone(ZoneId.of(properties.timeZone())).toInstant());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String formatAdminDateTime(String instant) {
        return Instant.parse(instant)
                .atZone(ZoneId.of(properties.timeZone()))
                .format(ADMIN_DATE_TIME) + " " + properties.timeZone();
    }

    private String linkTargetText(LinkTarget target) {
        return target == LinkTarget.COURSE ? "✨ курс" : "🌀 личная сессия";
    }

    private String shortId(String id) {
        return id == null || id.length() <= 8 ? id : id.substring(0, 8);
    }

    private String startText() {
        return """
                *Оксана Ремпе*
                Кармический психолог, регрессолог, гипнолог, мастер Рейки.

                Видящая. Чувствующая. Знающая.

                Но это всё просто иллюзия. На самом деле я — *То, что это Осознаёт*.

                Здесь я собрала всё главное: *курс «Познай себя. Пробуждение»*, личную сессию, тарифы, отзывы и связь со мной.
                """;
    }

    private String aboutText() {
        return """
                🌌 *Обо мне*

                *Ремпе Оксана Анатольевна*
                Человек, который нашёл Бога.
                Человек, который временно проживает на этой планете.

                Я кармический психолог, который много лет потратил на изучение тяжёлых судеб людей.

                Почему кто-то богатый, а кто-то бедный? Почему одни болеют, а другие нет? У одних жизнь бурная, у других тихая.

                Единственное, что одинаково: не важно, богат ты или нет, люди всё равно несчастны. Вот в чём парадокс. И это факт.

                *И я знаю ответ на этот вопрос.*

                Я веду через возвращение к себе: к честности, глубине, внутреннему знанию и живому контакту с тем, кто вы есть на самом деле.

                Я обучена многим инструментам: гипнозу, Рейки, регрессии и другим практикам. Но оказалось, что всё это пустое.

                Самое важное в жизни — *знакомство с собой*. Самое важное в жизни — *знакомство с Истинным Богом*.
                """;
    }

    private String courseText() {
        return """
                ✨ *Курс «Познай себя. Пробуждение»*

                Я создала этот курс для тех, кто устал искать истину снаружи и готов идти в себя.

                Это путь из состояния «жертвы обстоятельств» к состоянию *творца своей реальности*.

                Обучение длится *13 недель*: вы оплачиваете 12 недель, а 13-я неделя идёт в подарок.

                Пробуждение нельзя объяснить словами. Его можно только прожить самому.
                """;
    }

    private String tariffsText() {
        return """
                💳 *Выберите формат участия*

                🔥 *Полный курс* — 35 000 ₽
                Вся программа целиком. Вы закрываете финансовый вопрос один раз и спокойно идёте в обучение.

                💸 *Понедельная оплата* — 3 500 ₽
                Мягкий вход в курс. Оплата каждую пятницу до *21:00*. Занятия по субботам в *9:00 по Москве*.

                🌀 *Индивидуальная сессия* — 15 000 ₽
                Личная работа со мной и глубокий поиск причины повторяющегося сценария.

                🔁 *Повторное обучение* — 1 000 ₽ за неделю
                Для тех, кто уже проходил обучение. Оплата также в пятницу до *21:00*.
                """;
    }

    private String sessionText() {
        return """
                🌀 *Индивидуальная сессия*

                «Один сеанс, который разделит вашу жизнь на до и после».

                Если вы снова попадаете в одинаковые отношения, упираетесь в финансовый потолок или годами ходите вокруг одной проблемы, я не остаюсь на поверхности.

                За 2 часа мы спускаемся к первопричине в подсознании и развязываем узел. Это не магия, а *глубокая психологическая работа* с памятью и состоянием.

                Стоимость: *15 000 ₽*
                """;
    }

    private String rulesText() {
        return """
                ⚠️ *Правила пространства*

                Я подтверждаю участие после фактического зачисления средств. Скриншоты и обещания в личные сообщения не принимаются: всё прозрачно и проходит через банк.

                Для еженедельного формата оплата следующей недели вносится до пятницы, *21:00*.

                Если оплата не поступает вовремя, участие в следующей неделе не подтверждается. Пробуждение начинается с дисциплины и взаимного уважения.
                """;
    }

    private String contactsText() {
        return """
                📞 *Связь со мной*

                Здесь можно написать мне напрямую или подписаться на мои материалы.
                """;
    }

    private String shortLabel(String displayName, long userId) {
        String label = displayName == null || displayName.isBlank() ? "Пользователь " + userId : displayName;
        if (label.length() <= 32) {
            return label;
        }
        return label.substring(0, 29) + "...";
    }

    private Incoming parse(JsonNode update) {
        String updateType = firstTextField(update, "update_type", "type").orElse("");
        JsonNode message = update.path("message");
        JsonNode callback = update.path("callback");

        Long userId = firstLong(
                update.path("user").path("user_id"),
                update.path("user").path("id"),
                callback.path("user").path("user_id"),
                callback.path("user").path("id"),
                message.path("sender").path("user_id"),
                message.path("sender").path("id"),
                message.path("author").path("user_id"),
                message.path("author").path("id"),
                update.path("sender").path("user_id"),
                update.path("sender").path("id")
        ).orElse(null);

        Long chatId = firstLong(
                update.path("chat_id"),
                message.path("chat_id"),
                message.path("recipient").path("chat_id"),
                callback.path("message").path("chat_id")
        ).orElse(null);

        String text = firstText(
                message.path("body").path("text"),
                message.path("text"),
                update.path("text")
        ).orElse("");

        String callbackId = firstText(
                callback.path("callback_id"),
                update.path("callback_id")
        ).orElse(null);

        String payload = firstText(
                callback.path("payload"),
                update.path("payload")
        ).orElse(null);

        String displayName = firstText(
                update.path("user").path("name"),
                update.path("user").path("username"),
                callback.path("user").path("name"),
                callback.path("user").path("username"),
                message.path("sender").path("name"),
                message.path("sender").path("username")
        ).orElse("");

        if ("bot_started".equals(updateType) && payload == null) {
            payload = "MAIN";
        }
        return new Incoming(userId, chatId, text, callbackId, payload, displayName);
    }

    private Optional<String> firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText("").isBlank()) {
                return Optional.of(node.asText());
            }
        }
        return Optional.empty();
    }

    private Optional<String> firstTextField(JsonNode root, String... fields) {
        for (String field : fields) {
            Optional<String> value = firstText(root.path(field));
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private Optional<Long> firstLong(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                if (node.canConvertToLong()) {
                    return Optional.of(node.asLong());
                }
                String value = node.asText("");
                if (!value.isBlank()) {
                    try {
                        return Optional.of(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        // Try next field.
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record Incoming(Long userId, Long chatId, String text, String callbackId, String payload, String userDisplayName) {
    }

    private enum Step {
        WAITING_NAME,
        WAITING_PHONE,
        WAITING_EMAIL
    }

    private enum AdminLinkStep {
        WAITING_URL,
        WAITING_DATE_TIME
    }

    private record AdminLinkDraft(LinkTarget target, AdminLinkStep step, String url) {
    }

    private record UserState(Step step, Tariff tariff, String name, String phone, String email) {
        UserState withName(String name) {
            return new UserState(Step.WAITING_PHONE, tariff, name, phone, email);
        }

        UserState withPhone(String phone) {
            return new UserState(Step.WAITING_EMAIL, tariff, name, phone, email);
        }

        UserState withEmail(String email) {
            return new UserState(step, tariff, name, phone, email);
        }
    }
}
