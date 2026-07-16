package ru.psihmax.bot.max;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import ru.psihmax.bot.config.BotProperties;

@Component
public class MaxApiClient {
    private final BotProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MaxApiClient(BotProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    public UpdatesBatch getUpdates(Long marker) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder("limit=100&timeout=60&types=message_created,message_callback,bot_started");
        if (marker != null) {
            query.append("&marker=").append(marker);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri("/updates?" + query))
                .timeout(Duration.ofSeconds(80))
                .header("Authorization", properties.max().token())
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        JsonNode root = objectMapper.readTree(response.body());
        List<JsonNode> updates = new ArrayList<>();
        root.path("updates").forEach(updates::add);
        Long nextMarker = root.hasNonNull("marker") ? root.path("marker").asLong() : null;
        return new UpdatesBatch(updates, nextMarker);
    }

    public void sendMessageToUser(long userId, String text, List<List<InlineButton>> buttons) {
        sendMessage("user_id", userId, text, buttons);
    }

    public void sendMessageToChat(long chatId, String text, List<List<InlineButton>> buttons) {
        sendMessage("chat_id", chatId, text, buttons);
    }

    public void answerCallback(String callbackId, String notification) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("notification", notification);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri("/answers?callback_id=" + enc(callbackId)))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", properties.max().token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            send(request);
        } catch (Exception ignored) {
            // Callback notifications are best effort; the real navigation is sent as a new message.
        }
    }

    private void sendMessage(String idName, long id, String text, List<List<InlineButton>> buttons) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("text", text);
            body.put("format", "markdown");
            body.put("notify", true);

            if (buttons != null && !buttons.isEmpty()) {
                body.set("attachments", inlineKeyboard(buttons));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri("/messages?" + idName + "=" + id))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", properties.max().token())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            send(request);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot send MAX message", e);
        }
    }

    private ArrayNode inlineKeyboard(List<List<InlineButton>> rows) {
        ArrayNode attachments = objectMapper.createArrayNode();
        ObjectNode attachment = objectMapper.createObjectNode();
        attachment.put("type", "inline_keyboard");
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode buttons = objectMapper.createArrayNode();

        for (List<InlineButton> row : rows) {
            ArrayNode rowNode = objectMapper.createArrayNode();
            for (InlineButton button : row) {
                ObjectNode buttonNode = objectMapper.createObjectNode();
                buttonNode.put("type", button.type());
                buttonNode.put("text", button.text());
                if (button.payload() != null) {
                    buttonNode.put("payload", button.payload());
                }
                if (button.url() != null) {
                    buttonNode.put("url", button.url());
                }
                rowNode.add(buttonNode);
            }
            buttons.add(rowNode);
        }

        payload.set("buttons", buttons);
        attachment.set("payload", payload);
        attachments.add(attachment);
        return attachments;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("MAX API returned " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    private URI uri(String pathAndQuery) {
        String base = properties.max().apiBaseUrl();
        String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return URI.create(normalizedBase + pathAndQuery);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
