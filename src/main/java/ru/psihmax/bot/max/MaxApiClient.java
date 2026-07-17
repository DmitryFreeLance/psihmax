package ru.psihmax.bot.max;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    public void sendImageToUser(long userId, String text, String imageToken, List<List<InlineButton>> buttons) {
        sendImage("user_id", userId, text, imageToken, buttons);
    }

    public void sendImageToChat(long chatId, String text, String imageToken, List<List<InlineButton>> buttons) {
        sendImage("chat_id", chatId, text, imageToken, buttons);
    }

    public String uploadImage(Path image) {
        try {
            HttpRequest prepareRequest = HttpRequest.newBuilder()
                    .uri(uri("/uploads?type=image"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", properties.max().token())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            JsonNode uploadInfo = objectMapper.readTree(send(prepareRequest).body());
            String uploadUrl = uploadInfo.path("url").asText();
            String token = uploadInfo.path("token").asText();
            if (uploadUrl.isBlank()) {
                throw new IOException("MAX upload response has no url: " + uploadInfo);
            }

            HttpResponse<String> uploadResponse = uploadFile(uploadUrl, image);
            JsonNode uploadResult = tryReadJson(uploadResponse.body());
            if (token.isBlank() && uploadResult != null) {
                token = firstText(uploadResult.path("token"), uploadResult.path("retval").path("token")).orElse("");
            }
            if (token.isBlank()) {
                throw new IOException("MAX upload response has no token. Prepare: " + uploadInfo + ", upload: " + uploadResponse.body());
            }
            return token;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot upload image " + image, e);
        }
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
        sendMessage(idName, id, text, null, buttons);
    }

    private void sendImage(String idName, long id, String text, String imageToken, List<List<InlineButton>> buttons) {
        ObjectNode image = objectMapper.createObjectNode();
        image.put("type", "image");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("token", imageToken);
        image.set("payload", payload);
        sendMessage(idName, id, text, List.of(image), buttons);
    }

    private void sendMessage(String idName, long id, String text, List<ObjectNode> extraAttachments, List<List<InlineButton>> buttons) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("text", text);
            body.put("format", "markdown");
            body.put("notify", true);

            ArrayNode attachments = objectMapper.createArrayNode();
            if (extraAttachments != null) {
                extraAttachments.forEach(attachments::add);
            }
            if (buttons != null && !buttons.isEmpty()) {
                attachments.add(inlineKeyboard(buttons));
            }
            if (!attachments.isEmpty()) {
                body.set("attachments", attachments);
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

    private ObjectNode inlineKeyboard(List<List<InlineButton>> rows) {
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
        return attachment;
    }

    private HttpResponse<String> uploadFile(String uploadUrl, Path image) throws IOException, InterruptedException {
        String boundary = "----psihmax-" + UUID.randomUUID();
        String contentType = Files.probeContentType(image);
        if (contentType == null || contentType.isBlank()) {
            contentType = "application/octet-stream";
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Disposition: form-data; name=\"data\"; filename=\"" + image.getFileName() + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(Files.readAllBytes(image));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest uploadRequest = HttpRequest.newBuilder()
                .uri(URI.create(uploadUrl))
                .timeout(Duration.ofMinutes(3))
                .header("Authorization", properties.max().token())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        return send(uploadRequest);
    }

    private JsonNode tryReadJson(String body) {
        try {
            if (body == null || body.isBlank()) {
                return null;
            }
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private java.util.Optional<String> firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            if (node != null && !node.isMissingNode() && !node.isNull() && !node.asText("").isBlank()) {
                return java.util.Optional.of(node.asText());
            }
        }
        return java.util.Optional.empty();
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
