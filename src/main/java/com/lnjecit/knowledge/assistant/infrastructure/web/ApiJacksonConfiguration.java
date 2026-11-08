package com.lnjecit.knowledge.assistant.infrastructure.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

@Configuration(proxyBeanMethods = false)
public class ApiJacksonConfiguration {
    @Bean
    public JsonMapperBuilderCustomizer longAsString() {
        // 所有 Long（包含非 ID 字段）均输出字符串；Integer 错误码保持数值。
        return builder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.addModule(module);
        };
    }
}
