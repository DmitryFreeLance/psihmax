package ru.psihmax.bot.max;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import ru.psihmax.bot.BotService;

@Component
public class LongPollingRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(LongPollingRunner.class);

    private final MaxApiClient maxApiClient;
    private final BotService botService;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread pollingThread;

    public LongPollingRunner(MaxApiClient maxApiClient, BotService botService) {
        this.maxApiClient = maxApiClient;
        this.botService = botService;
    }

    @Override
    public void run(ApplicationArguments args) {
        pollingThread = Thread.ofVirtual().name("max-long-polling").start(this::poll);
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollingThread != null) {
            pollingThread.interrupt();
        }
    }

    private void poll() {
        Long marker = null;
        while (running.get()) {
            try {
                UpdatesBatch batch = maxApiClient.getUpdates(marker);
                for (var update : batch.updates()) {
                    botService.handleUpdate(update);
                }
                if (batch.marker() != null) {
                    marker = batch.marker();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("MAX long polling failed, retrying", e);
                sleepQuietly(Duration.ofSeconds(2));
            }
        }
    }

    private void sleepQuietly(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
