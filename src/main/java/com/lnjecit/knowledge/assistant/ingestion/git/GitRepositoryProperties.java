package com.lnjecit.knowledge.assistant.ingestion.git;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties("assistant.git")
public class GitRepositoryProperties implements InitializingBean {
    private boolean enabled;
    private Set<Long> allowedUserIds = Set.of();
    private Map<String, Repository> repositories = Map.of();
    private String executable = "git";
    private Duration commandTimeout = Duration.ofSeconds(5);
    private int maxOutputBytes = 8 * 1024 * 1024;
    private Duration snapshotTtl = Duration.ofMinutes(15);
    private int snapshotCapacity = 256;

    public record Repository(String name, String path) {}

    @Override
    public void afterPropertiesSet() {
        if (executable.isBlank()
                || commandTimeout.isNegative()
                || commandTimeout.isZero()
                || maxOutputBytes < 1
                || snapshotTtl.isNegative()
                || snapshotTtl.isZero()
                || snapshotCapacity < 1) {
            throw new IllegalArgumentException("assistant.git 命令或快照配置不合法");
        }
    }
}
