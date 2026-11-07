package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class OnlineAuthorizationTests {
    @Test
    void modelRequiresSeparateAuthorization() {
        // 仅检查门禁，不构造客户端、不访问供应商。
        assertThatThrownBy(() -> ModelOnlineIT.requireAuthorization(Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ModelOnlineIT.requireAuthorization(Map.of("ASSISTANT_MODEL_ENABLED", "true")))
                .isInstanceOf(IllegalStateException.class);
        ModelOnlineIT.requireAuthorization(
                Map.of("ASSISTANT_MODEL_ENABLED", "true", "ASSISTANT_MODEL_ONLINE_AUTHORIZED", "true"));
    }
}
