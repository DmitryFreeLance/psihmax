package ru.psihmax.bot.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.psihmax.bot.BotService;
import ru.psihmax.bot.payment.YooKassaClient;
import ru.psihmax.bot.store.OrderStore;

@RestController
public class YooKassaWebhookController {
    private static final Logger log = LoggerFactory.getLogger(YooKassaWebhookController.class);

    private final YooKassaClient yooKassaClient;
    private final OrderStore orderStore;
    private final BotService botService;

    public YooKassaWebhookController(YooKassaClient yooKassaClient, OrderStore orderStore, BotService botService) {
        this.yooKassaClient = yooKassaClient;
        this.orderStore = orderStore;
        this.botService = botService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/webhooks/yookassa")
    public ResponseEntity<Map<String, String>> yookassa(@RequestBody JsonNode webhook) {
        String event = webhook.path("event").asText("");
        String paymentId = webhook.path("object").path("id").asText("");
        if (!"payment.succeeded".equals(event) || paymentId.isBlank()) {
            return ResponseEntity.ok(Map.of("status", "ignored"));
        }

        JsonNode payment = yooKassaClient.getPayment(paymentId);
        boolean succeeded = "succeeded".equals(payment.path("status").asText("")) && payment.path("paid").asBoolean(false);
        if (!succeeded) {
            log.warn("YooKassa webhook says succeeded, but API verification did not confirm payment {}", paymentId);
            return ResponseEntity.ok(Map.of("status", "not_confirmed"));
        }

        orderStore.markPaid(paymentId).ifPresentOrElse(
                botService::notifyPaymentSucceeded,
                () -> log.info("YooKassa payment {} is unknown or already processed", paymentId)
        );

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
