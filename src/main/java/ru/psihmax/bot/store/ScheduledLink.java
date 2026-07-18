package ru.psihmax.bot.store;

import java.util.ArrayList;
import java.util.List;

public record ScheduledLink(
        String id,
        LinkTarget target,
        String title,
        String url,
        String sendAt,
        String status,
        String createdAt,
        List<LinkDelivery> deliveries
) {
    public ScheduledLink {
        deliveries = deliveries == null ? new ArrayList<>() : new ArrayList<>(deliveries);
    }

    public boolean active() {
        return !"CANCELED".equals(status);
    }
}
