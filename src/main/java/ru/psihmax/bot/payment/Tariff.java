package ru.psihmax.bot.payment;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;

public enum Tariff {
    FULL("full", "Полный курс «Познай себя. Пробуждение»", new BigDecimal("35000.00"),
            "13 недель обучения, 12 оплачиваемых недель и 13-я неделя в подарок."),
    WEEKLY("weekly", "Понедельная оплата курса", new BigDecimal("3500.00"),
            "Одна учебная неделя. Оплата по пятницам до 21:00."),
    SESSION("session", "Индивидуальная сессия-трансформация", new BigDecimal("15000.00"),
            "Личная консультация и глубокое погружение на 2 часа."),
    REPEAT("repeat", "Повторное обучение", new BigDecimal("1000.00"),
            "Для тех, кто уже проходил обучение. Оплата по пятницам до 21:00.");

    private final String code;
    private final String title;
    private final BigDecimal amount;
    private final String description;

    Tariff(String code, String title, BigDecimal amount, String description) {
        this.code = code;
        this.title = title;
        this.amount = amount;
        this.description = description;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public BigDecimal amount() {
        return amount;
    }

    public String description() {
        return description;
    }

    public String amountText() {
        return amount.stripTrailingZeros().toPlainString() + " руб.";
    }

    public static Optional<Tariff> byCode(String code) {
        return Arrays.stream(values()).filter(tariff -> tariff.code.equals(code)).findFirst();
    }
}
