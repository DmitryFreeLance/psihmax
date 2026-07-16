package ru.psihmax.bot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PsihmaxBotApplication {
    public static void main(String[] args) {
        SpringApplication.run(PsihmaxBotApplication.class, args);
    }
}
