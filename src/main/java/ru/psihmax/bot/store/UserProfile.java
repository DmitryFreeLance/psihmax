package ru.psihmax.bot.store;

public record UserProfile(
        long userId,
        String displayName,
        String lastSeenAt
) {
}
