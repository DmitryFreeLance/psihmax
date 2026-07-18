package ru.psihmax.bot.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "bot")
public record BotProperties(
        String dataFile,
        String reviewsDir,
        String videosDir,
        String timeZone,
        @Valid Max max,
        @Valid YooKassa yookassa,
        @Valid Links links
) {
    public record Max(
            @NotBlank String token,
            @NotBlank String apiBaseUrl,
            long adminUserId
    ) {
    }

    public record YooKassa(
            @NotBlank String shopId,
            @NotBlank String secretKey,
            @NotBlank String returnUrl
    ) {
    }

    public record Links(
            @NotBlank String bookUrl,
            @NotBlank String vkUrl,
            @NotBlank String telegramUrl
    ) {
    }
}
