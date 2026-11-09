package com.lnjecit.knowledge.assistant.infrastructure.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties("assistant.worker")
public class WorkerProperties implements InitializingBean {
    private boolean enabled = false;
    private Duration scanInterval = Duration.ofSeconds(5);
    private int batchSize = 1;
    private Duration leaseDuration = Duration.ofMinutes(15);
    private Duration maxTaskDuration = Duration.ofMinutes(10);
    private int maxAttempts = 4;
    private List<Duration> retryBackoff =
            List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(45));
    private double retryJitter = 0.2;

    public void afterPropertiesSet() {
        if (!positive(scanInterval)
                || !positive(leaseDuration)
                || !positive(maxTaskDuration)
                || maxTaskDuration.compareTo(leaseDuration) >= 0
                || maxAttempts <= 0
                || retryBackoff == null
                || retryBackoff.size() != maxAttempts - 1
                || retryBackoff.stream().anyMatch(d -> !positive(d))
                || !Double.isFinite(retryJitter)
                || retryJitter < 0
                || retryJitter > 1
                || batchSize < 1
                || batchSize > 10) throw new IllegalArgumentException("Invalid worker configuration");
    }

    private static boolean positive(Duration d) {
        return d != null
                && !d.isNegative()
                && d.compareTo(Duration.ofMillis(1)) >= 0
                && d.compareTo(Duration.ofDays(365)) <= 0
                && d.equals(Duration.ofMillis(d.toMillis()));
    }

    public long retryDelayMillis(int attempt) {
        if (attempt < 1 || attempt >= maxAttempts) return 0;
        double factor = 1 + ThreadLocalRandom.current().nextDouble(-1, 1) * retryJitter;
        return Math.round(retryBackoff.get(attempt - 1).toMillis() * factor);
    }
}
