package ru.psihmax.bot.store;

public record LinkDelivery(
        long userId,
        String tariffCode,
        String sentAt
) {
}
