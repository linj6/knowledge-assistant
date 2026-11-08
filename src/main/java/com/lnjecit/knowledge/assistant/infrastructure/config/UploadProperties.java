package com.lnjecit.knowledge.assistant.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties("assistant.upload")
public class UploadProperties implements InitializingBean {
    private int maxFileBytes = 10485760;

    @Override
    public void afterPropertiesSet() {
        if (maxFileBytes < 1 || maxFileBytes > 10485760) throw new IllegalArgumentException("Invalid upload limit");
    }
}
