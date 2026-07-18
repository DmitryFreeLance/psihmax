package ru.psihmax.bot.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import ru.psihmax.bot.config.BotProperties;

@Component
public class OrderStore {
    private final ObjectMapper objectMapper;
    private final Path dataFile;
    private final Map<String, PaymentOrder> orders;

    public OrderStore(BotProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dataFile = Path.of(properties.dataFile());
        this.orders = load();
    }

    public synchronized void save(PaymentOrder order) {
        orders.put(order.paymentId(), order);
        flush();
    }

    public synchronized Optional<PaymentOrder> find(String paymentId) {
        return Optional.ofNullable(orders.get(paymentId));
    }

    public synchronized Optional<PaymentOrder> markPaid(String paymentId) {
        PaymentOrder order = orders.get(paymentId);
        if (order == null || "PAID".equals(order.status())) {
            return Optional.empty();
        }
        PaymentOrder paid = order.paid(Instant.now().toString());
        orders.put(paymentId, paid);
        flush();
        return Optional.of(paid);
    }

    public synchronized List<PaymentOrder> paidOrders() {
        return orders.values().stream()
                .filter(order -> "PAID".equals(order.status()))
                .sorted(Comparator.comparing(PaymentOrder::paidAt, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    public synchronized long paidCount(long userId, String tariffCode) {
        return orders.values().stream()
                .filter(order -> "PAID".equals(order.status()))
                .filter(order -> order.userId() == userId)
                .filter(order -> tariffCode.equals(order.tariffCode()))
                .count();
    }

    private Map<String, PaymentOrder> load() {
        if (!Files.exists(dataFile)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(dataFile.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read orders file " + dataFile, e);
        }
    }

    private void flush() {
        try {
            Path parent = dataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), orders);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write orders file " + dataFile, e);
        }
    }
}
