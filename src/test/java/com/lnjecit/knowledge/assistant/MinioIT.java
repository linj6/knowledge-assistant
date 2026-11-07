package com.lnjecit.knowledge.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class MinioIT {
    @Container
    static final GenericContainer<?> minio = new GenericContainer<>(
                    DockerImageName.parse("knowledge-assistant-minio:RELEASE.2025-09-07T16-13-09Z-local"))
            .withEnv("MINIO_ROOT_USER", "m007-user")
            .withEnv("MINIO_ROOT_PASSWORD", "m007-test-password")
            .withCommand("server", "/data", "--console-address", ":9001")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))
            .withStartupTimeout(Duration.ofSeconds(120))
            .withImagePullPolicy(image -> false);

    private String execute(String command) throws Exception {
        org.testcontainers.containers.Container.ExecResult result = minio.execInContainer("sh", "-c", command);
        assertThat(result.getExitCode()).isZero();
        return result.getStdout();
    }

    @Test
    void objectRoundTrip() throws Exception {
        // 凭据仅在容器内展开，不输出客户端配置或响应正文。
        execute("mc alias set probe http://localhost:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null");
        String bucket = "m007-" + java.util.UUID.randomUUID();
        execute("mc mb probe/" + bucket);
        try {
            execute("printf 'Knowledge Assistant 中文测试' > /tmp/source && mc cp /tmp/source probe/" + bucket
                    + "/object >/dev/null");
            assertThat(execute("mc cat probe/" + bucket + "/object")).isEqualTo("Knowledge Assistant 中文测试");
        } finally {
            execute("mc rm --force probe/" + bucket + "/object && mc rb probe/" + bucket);
        }
    }
}
