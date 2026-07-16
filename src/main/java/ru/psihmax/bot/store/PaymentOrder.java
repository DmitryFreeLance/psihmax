package ru.psihmax.bot.store;

public record PaymentOrder(
        String paymentId,
        long userId,
        Long chatId,
        String userDisplayName,
        String customerName,
        String phone,
        String tariffCode,
        String tariffTitle,
        String amountValue,
        String confirmationUrl,
        String status,
        String createdAt,
        String paidAt
) {
    public PaymentOrder paid(String paidAt) {
        return new PaymentOrder(
                paymentId,
                userId,
                chatId,
                userDisplayName,
                customerName,
                phone,
                tariffCode,
                tariffTitle,
                amountValue,
                confirmationUrl,
                "PAID",
                createdAt,
                paidAt
        );
    }
}
