package ru.psihmax.bot.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import ru.psihmax.bot.config.BotProperties;

@Component
public class ScheduledLinkStore {
    private final ObjectMapper objectMapper;
    private final Path dataFile;
    private final List<ScheduledLink> links;

    public ScheduledLinkStore(BotProperties properties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        Path ordersPath = Path.of(properties.dataFile());
        Path dataDir = ordersPath.getParent() == null ? Path.of("data") : ordersPath.getParent();
        this.dataFile = dataDir.resolve("scheduled-links.json");
        this.links = load();
    }

    public synchronized ScheduledLink create(LinkTarget target, String title, String url, String sendAt) {
        ScheduledLink link = new ScheduledLink(
                UUID.randomUUID().toString(),
                target,
                title,
                url,
                sendAt,
                "ACTIVE",
                Instant.now().toString(),
                List.of()
        );
        links.add(link);
        flush();
        return link;
    }

    public synchronized List<ScheduledLink> activeLinks() {
        return links.stream()
                .filter(ScheduledLink::active)
                .sorted(Comparator.comparing(ScheduledLink::sendAt))
                .toList();
    }

    public synchronized List<ScheduledLink> activeLinks(LinkTarget target) {
        return activeLinks().stream()
                .filter(link -> link.target() == target)
                .toList();
    }

    public synchronized Optional<ScheduledLink> find(String id) {
        return links.stream().filter(link -> link.id().equals(id)).findFirst();
    }

    public synchronized boolean cancel(String id) {
        for (int i = 0; i < links.size(); i++) {
            ScheduledLink link = links.get(i);
            if (link.id().equals(id) && link.active()) {
                links.set(i, new ScheduledLink(
                        link.id(),
                        link.target(),
                        link.title(),
                        link.url(),
                        link.sendAt(),
                        "CANCELED",
                        link.createdAt(),
                        link.deliveries()
                ));
                flush();
                return true;
            }
        }
        return false;
    }

    public synchronized boolean addDelivery(String id, LinkDelivery delivery) {
        for (int i = 0; i < links.size(); i++) {
            ScheduledLink link = links.get(i);
            if (link.id().equals(id) && link.active()) {
                boolean alreadySent = link.deliveries().stream()
                        .anyMatch(existing -> existing.userId() == delivery.userId());
                if (alreadySent) {
                    return false;
                }
                List<LinkDelivery> deliveries = new ArrayList<>(link.deliveries());
                deliveries.add(delivery);
                links.set(i, new ScheduledLink(
                        link.id(),
                        link.target(),
                        link.title(),
                        link.url(),
                        link.sendAt(),
                        link.status(),
                        link.createdAt(),
                        deliveries
                ));
                flush();
                return true;
            }
        }
        return false;
    }

    public synchronized long deliveredCount(LinkTarget target, long userId, String tariffCode) {
        return links.stream()
                .filter(link -> link.target() == target)
                .flatMap(link -> link.deliveries().stream())
                .filter(delivery -> delivery.userId() == userId)
                .filter(delivery -> tariffCode.equals(delivery.tariffCode()))
                .count();
    }

    private List<ScheduledLink> load() {
        if (!Files.exists(dataFile)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(dataFile.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read scheduled links file " + dataFile, e);
        }
    }

    private void flush() {
        try {
            Path parent = dataFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(dataFile.toFile(), links);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write scheduled links file " + dataFile, e);
        }
    }
}
