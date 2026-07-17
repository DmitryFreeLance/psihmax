package ru.psihmax.bot.store;

public record ReviewCacheEntry(
        String token,
        long size,
        long lastModifiedMillis
) {
}
