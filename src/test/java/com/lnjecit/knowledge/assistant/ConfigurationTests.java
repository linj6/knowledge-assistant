package com.lnjecit.knowledge.assistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.system.*;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import static org.assertj.core.api.Assertions.*;
@ExtendWith(OutputCaptureExtension.class)
class ConfigurationTests {
 @Configuration @EnableConfigurationProperties(ModelProperties.class) static class Config {}
 ApplicationContextRunner runner=new ApplicationContextRunner().withUserConfiguration(Config.class);
 @Test void valid() { runner.withPropertyValues("assistant.model.enabled=true","assistant.model.api-key=TEST_SECRET","assistant.model.timeout-seconds=15").run(c->{assertThat(c).hasNotFailed(); assertThat(c.getBean(ModelProperties.class).getTimeoutSeconds()).isEqualTo(15);}); }
 @Test void disabled() { runner.run(c->assertThat(c).hasNotFailed()); }
 @Test void required() { runner.withPropertyValues("assistant.model.enabled=true").run(c->assertThat(c).hasFailed()); }
 @Test void invalidAndSafe(CapturedOutput output) { runner.withPropertyValues("assistant.model.api-key=TEST_SECRET","assistant.model.timeout-seconds=0").run(c->assertThat(c).hasFailed()); assertThat(output.getAll()).doesNotContain("TEST_SECRET"); }
 @Test void invalidType() { runner.withPropertyValues("assistant.model.timeout-seconds=invalid").run(c->assertThat(c).hasFailed()); }
}
