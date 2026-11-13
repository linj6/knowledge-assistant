package com.lnjecit.knowledge.assistant.ingestion.upload;

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
    private int maxFileBytes = 2097152;

    @Override
    public void afterPropertiesSet() {
        if (maxFileBytes < 1 || maxFileBytes > 2097152)
            throw new IllegalArgumentException("assistant.upload.max-file-bytes must be between 1 and 2097152");
    }
}
