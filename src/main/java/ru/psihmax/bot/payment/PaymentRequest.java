package ru.psihmax.bot.payment;

public record PaymentRequest(
        long userId,
        Long chatId,
        String userDisplayName,
        String customerName,
        String phone,
        String email,
        Tariff tariff
) {
}
