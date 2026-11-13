package com.lnjecit.knowledge.assistant.infrastructure.logging;

import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import com.lnjecit.knowledge.assistant.infrastructure.elasticsearch.EsIndexNames;
import com.lnjecit.knowledge.assistant.ingestion.chunk.ChunkingProperties;
import com.lnjecit.knowledge.assistant.ingestion.embedding.EmbeddingProperties;
import com.lnjecit.knowledge.assistant.ingestion.task.ImportEvents;
import com.lnjecit.knowledge.assistant.ingestion.task.WorkerStatusLogger;
import com.lnjecit.knowledge.assistant.ingestion.upload.UploadProperties;
import com.lnjecit.knowledge.assistant.rag.context.ContextProperties;
import com.lnjecit.knowledge.assistant.rag.generation.AnswerGenerator;
import com.lnjecit.knowledge.assistant.retrieval.model.RetrievalParameters;
import com.lnjecit.knowledge.assistant.retrieval.rerank.RerankProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** 只展示最终绑定的普通配置，不读取密钥字段，也不连接外部服务。 */
@Component
public class StartupConfigLogger {
    private static final Logger LOG = LoggerFactory.getLogger(StartupConfigLogger.class);
    private final Environment environment;
    private final ModelProperties model;
    private final RerankProperties rerank;
    private final EmbeddingProperties embedding;
    private final UploadProperties upload;
    private final ChunkingProperties chunking;
    private final ContextProperties context;
    private final WorkerStatusLogger worker;
    private final AtomicBoolean reported = new AtomicBoolean();

    public StartupConfigLogger(
            Environment environment,
            ModelProperties model,
            RerankProperties rerank,
            EmbeddingProperties embedding,
            UploadProperties upload,
            ChunkingProperties chunking,
            ContextProperties context,
            WorkerStatusLogger worker) {
        this.environment = environment;
        this.model = model;
        this.rerank = rerank;
        this.embedding = embedding;
        this.upload = upload;
        this.chunking = chunking;
        this.context = context;
        this.worker = worker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        if (!reported.compareAndSet(false, true)) return;
        section(
                "APPLICATION",
                () -> emit(
                        "APPLICATION",
                        "applicationName",
                        property("spring.application.name"),
                        "activeProfiles",
                        Arrays.toString(environment.getActiveProfiles()),
                        "defaultProfiles",
                        Arrays.toString(environment.getDefaultProfiles()),
                        "serverPort",
                        environment.getProperty("local.server.port", environment.getProperty("server.port", "8080")),
                        "contextPath",
                        environment.getProperty("server.servlet.context-path", ""),
                        "servletPath",
                        environment.getProperty("spring.mvc.servlet.path", "/"),
                        "javaVersion",
                        System.getProperty("java.version")));
        section("WORKER", worker::report);
        section("EMBEDDING", () -> {
            Object modelLimit = "UNCONFIGURED";
            Object effective = "UNAVAILABLE";
            try {
                modelLimit = embedding.modelMaxBatchSize(model.getEmbeddingModel());
                effective = embedding.effectiveBatchSize(model.getEmbeddingModel());
            } catch (IllegalStateException unavailable) {
                // 模型关闭或未知时仍输出已知配置；真正执行前由服务拒绝未明确的限制。
            }
            emit(
                    "EMBEDDING",
                    "modelEnabled",
                    model.isEnabled(),
                    "onlineAuthorized",
                    property("assistant.model.online-authorized"),
                    "modelName",
                    model.getEmbeddingModel(),
                    "dimensions",
                    model.getDimensions(),
                    "configuredBatchSize",
                    embedding.getBatchSize(),
                    "modelMaxBatchSize",
                    modelLimit,
                    "effectiveBatchSize",
                    effective,
                    "timeoutSeconds",
                    model.getTimeoutSeconds(),
                    "baseUrl",
                    safeUrl(model.getBaseUrl()));
        });
        section(
                "CHAT",
                () -> emit(
                        "CHAT",
                        "modelName",
                        model.getChatModel(),
                        "modelEnabled",
                        model.isEnabled(),
                        "timeoutSeconds",
                        model.getTimeoutSeconds()));
        section(
                "RERANK",
                () -> emit(
                        "RERANK",
                        "enabled",
                        rerank.isEnabled(),
                        "onlineAuthorized",
                        property("assistant.rerank.online-authorized"),
                        "modelName",
                        rerank.getModel(),
                        "endpoint",
                        safeUrl(rerank.getEndpoint()),
                        "timeoutSeconds",
                        rerank.getTimeoutSeconds()));
        section(
                "POSTGRESQL",
                () -> emit(
                        "POSTGRESQL",
                        "datasourceUrl",
                        safeUrl(environment.getProperty("spring.datasource.url")),
                        "datasourceDriver",
                        property("spring.datasource.driver-class-name"),
                        "datasourceUsername",
                        property("spring.datasource.username"),
                        "flywayEnabled",
                        environment.getProperty("spring.flyway.enabled", "true")));
        section(
                "ELASTICSEARCH",
                () -> emit(
                        "ELASTICSEARCH",
                        "enabled",
                        environment.getProperty("assistant.elasticsearch.enabled", "false"),
                        "endpoint",
                        safeUrl(environment.getProperty("assistant.elasticsearch.endpoint", "http://localhost:9200")),
                        "indexName",
                        EsIndexNames.CHUNKS));
        section(
                "MINIO",
                () -> emit(
                        "MINIO",
                        "endpoint",
                        safeUrl(environment.getProperty("assistant.minio.endpoint")),
                        "bucket",
                        property("assistant.minio.bucket")));
        section(
                "UPLOAD_CHUNKING",
                () -> emit(
                        "UPLOAD_CHUNKING",
                        "maxFileBytes",
                        upload.getMaxFileBytes(),
                        "chunkingStrategy",
                        chunking.getStrategy(),
                        "chunkSize",
                        chunking.getChunkSize(),
                        "chunkOverlap",
                        chunking.getChunkOverlap(),
                        "chunkingVersion",
                        chunking.getVersion()));
        section(
                "RAG",
                () -> emit(
                        "RAG",
                        "contextTokenBudget",
                        context.getEstimatedTokenBudget(),
                        "contextMaxCharacters",
                        context.getMaxCharacters(),
                        "promptVersion",
                        AnswerGenerator.PROMPT_VERSION));
        section("RETRIEVAL", () -> {
            RetrievalParameters defaults = RetrievalParameters.defaults();
            emit(
                    "RETRIEVAL",
                    "defaultVectorTopK",
                    defaults.vectorTopK(),
                    "defaultBm25TopK",
                    defaults.bm25TopK(),
                    "defaultRrfK",
                    defaults.rrfK(),
                    "defaultFusionTopK",
                    defaults.fusionTopK(),
                    "defaultFinalTopK",
                    defaults.finalTopK());
        });
        // 私有校验范围和策略默认值不另抄一份；不为日志新增业务配置。
    }

    private String property(String key) {
        return environment.getProperty(key, "UNSET");
    }

    private static void section(String name, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException unavailable) {
            ImportEvents.warn(LOG, "event=APP_CONFIG section={} status=UNAVAILABLE", name);
        }
    }

    private static void emit(String section, Object... fields) {
        StringBuilder message = new StringBuilder("event=APP_CONFIG section=").append(section);
        for (int i = 0; i < fields.length; i += 2)
            message.append(' ').append(fields[i]).append('=').append(fields[i + 1]);
        ImportEvents.info(LOG, "{}", FailureLog.redact(message.toString()));
    }

    public static String safeUrl(String value) {
        if (value == null || value.isBlank()) return "UNSET";
        try {
            boolean jdbc = value.startsWith("jdbc:");
            URI uri = new URI(jdbc ? value.substring(5) : value);
            if (uri.getHost() == null || uri.getScheme() == null) return "INVALID_URL";
            // 查询参数和 fragment 可能包含任意认证字段，统一不展示；不把解析失败的原串写入日志。
            return (jdbc ? "jdbc:" : "")
                    + new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null);
        } catch (URISyntaxException invalid) {
            return "INVALID_URL";
        }
    }
}
