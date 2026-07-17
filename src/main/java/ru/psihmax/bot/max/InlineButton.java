package ru.psihmax.bot.max;

public record InlineButton(String type, String text, String payload, String url) {
    public static InlineButton callback(String text, String payload) {
        return new InlineButton("message", text, payload, null);
    }

    public static InlineButton link(String text, String url) {
        return new InlineButton("link", text, null, url);
    }
}
