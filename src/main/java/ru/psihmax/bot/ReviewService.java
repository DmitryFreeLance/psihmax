package ru.psihmax.bot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import ru.psihmax.bot.config.BotProperties;
import ru.psihmax.bot.max.MaxApiClient;
import ru.psihmax.bot.store.ReviewCacheEntry;

@Service
public class ReviewService {
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".gif", ".tiff", ".bmp", ".heic", ".webp"
    );

    private final BotProperties properties;
    private final MaxApiClient maxApiClient;
    private final ObjectMapper objectMapper;
    private final Path cacheFile;
    private final Map<String, ReviewCacheEntry> cache;

    public ReviewService(BotProperties properties, MaxApiClient maxApiClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.maxApiClient = maxApiClient;
        this.objectMapper = objectMapper;
        Path dataPath = Path.of(properties.dataFile());
        Path dataDir = dataPath.getParent() == null ? Path.of("data") : dataPath.getParent();
        this.cacheFile = dataDir.resolve("review-cache.json");
        this.cache = loadCache();
    }

    public Optional<ReviewPage> getPage(int requestedPage) {
        List<Path> images = images();
        if (images.isEmpty()) {
            return Optional.empty();
        }

        int page = Math.max(0, Math.min(requestedPage, images.size() - 1));
        Path image = images.get(page);
        return Optional.of(new ReviewPage(page, images.size(), image.getFileName().toString(), tokenFor(image)));
    }

    public int count() {
        return images().size();
    }

    private synchronized String tokenFor(Path image) {
        try {
            Path normalized = image.toAbsolutePath().normalize();
            String key = normalized.toString();
            long size = Files.size(normalized);
            long lastModifiedMillis = Files.getLastModifiedTime(normalized).toMillis();
            ReviewCacheEntry entry = cache.get(key);
            if (entry != null
                    && entry.size() == size
                    && entry.lastModifiedMillis() == lastModifiedMillis
                    && entry.token() != null
                    && !entry.token().isBlank()) {
                return entry.token();
            }

            String token = maxApiClient.uploadImage(normalized);
            cache.put(key, new ReviewCacheEntry(token, size, lastModifiedMillis));
            flushCache();
            return token;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare review image " + image, e);
        }
    }

    private List<Path> images() {
        Path dir = Path.of(properties.reviewsDir());
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isImage)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), ReviewService::compareNaturally))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read reviews dir " + dir, e);
        }
    }

    private boolean isImage(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return IMAGE_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    private Map<String, ReviewCacheEntry> loadCache() {
        if (!Files.exists(cacheFile)) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(cacheFile.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read review cache " + cacheFile, e);
        }
    }

    private void flushCache() {
        try {
            Path parent = cacheFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), cache);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write review cache " + cacheFile, e);
        }
    }

    private static int compareNaturally(String left, String right) {
        int li = 0;
        int ri = 0;
        while (li < left.length() && ri < right.length()) {
            char lc = left.charAt(li);
            char rc = right.charAt(ri);
            if (Character.isDigit(lc) && Character.isDigit(rc)) {
                int lend = numberEnd(left, li);
                int rend = numberEnd(right, ri);
                long ln = Long.parseLong(left.substring(li, lend));
                long rn = Long.parseLong(right.substring(ri, rend));
                int cmp = Long.compare(ln, rn);
                if (cmp != 0) {
                    return cmp;
                }
                li = lend;
                ri = rend;
                continue;
            }
            int cmp = Character.compare(Character.toLowerCase(lc), Character.toLowerCase(rc));
            if (cmp != 0) {
                return cmp;
            }
            li++;
            ri++;
        }
        return Integer.compare(left.length(), right.length());
    }

    private static int numberEnd(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isDigit(value.charAt(index))) {
            index++;
        }
        return index;
    }

    public record ReviewPage(int page, int total, String fileName, String token) {
    }
}
