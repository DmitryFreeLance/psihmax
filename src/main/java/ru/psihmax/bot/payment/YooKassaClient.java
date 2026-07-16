package ru.psihmax.bot.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.psihmax.bot.config.BotProperties;

@Component
public class YooKassaClient {
    private static final String API_BASE_URL = "https://api.yookassa.ru/v3";

    private final BotProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public YooKassaClient(BotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public CreatedPayment createPayment(PaymentRequest paymentRequest) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode amount = body.putObject("amount");
            amount.put("value", paymentRequest.tariff().amount().setScale(2, RoundingMode.HALF_UP).toPlainString());
            amount.put("currency", "RUB");
            body.put("capture", true);
            body.put("description", paymentRequest.tariff().title());

            ObjectNode confirmation = body.putObject("confirmation");
            confirmation.put("type", "redirect");
            confirmation.put("return_url", properties.yookassa().returnUrl());

            ObjectNode metadata = body.putObject("metadata");
            metadata.put("max_user_id", paymentRequest.userId());
            if (paymentRequest.chatId() != null) {
                metadata.put("max_chat_id", paymentRequest.chatId());
            }
            metadata.put("customer_name", paymentRequest.customerName());
            metadata.put("phone", paymentRequest.phone());
            metadata.put("tariff_code", paymentRequest.tariff().code());
            metadata.put("tariff_title", paymentRequest.tariff().title());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE_URL + "/payments"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", basicAuth())
                    .header("Idempotence-Key", UUID.randomUUID().toString())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            JsonNode root = objectMapper.readTree(send(request).body());
            String paymentId = root.path("id").asText();
            String confirmationUrl = root.path("confirmation").path("confirmation_url").asText();
            if (paymentId.isBlank() || confirmationUrl.isBlank()) {
                throw new IOException("YooKassa response has no payment id or confirmation url: " + root);
            }
            return new CreatedPayment(paymentId, confirmationUrl);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create YooKassa payment", e);
        }
    }

    public JsonNode getPayment(String paymentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_BASE_URL + "/payments/" + paymentId))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", basicAuth())
                    .GET()
                    .build();
            return objectMapper.readTree(send(request).body());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fetch YooKassa payment " + paymentId, e);
        }
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("YooKassa API returned " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    private String basicAuth() {
        String raw = properties.yookassa().shopId() + ":" + properties.yookassa().secretKey();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
