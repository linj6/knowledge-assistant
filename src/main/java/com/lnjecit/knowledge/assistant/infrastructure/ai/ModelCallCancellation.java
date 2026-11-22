package com.lnjecit.knowledge.assistant.infrastructure.ai;

import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import okhttp3.Call;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

public final class ModelCallCancellation {
    private static final ThreadLocal<ModelCallCancellation> CURRENT = new ThreadLocal<>();

    private volatile boolean cancelled;
    private Call call;

    public <T> T run(Supplier<T> action) {
        CURRENT.set(this);
        try {
            checkpoint();
            return action.get();
        } finally {
            CURRENT.remove();
        }
    }

    public synchronized void cancel() {
        cancelled = true;
        if (call != null) {
            call.cancel();
        }
    }

    public static void checkpoint() {
        ModelCallCancellation current = CURRENT.get();
        if (current != null && current.cancelled) {
            throw new CancellationException();
        }
    }

    public static <T> T duringModelCall(Supplier<T> action) {
        ModelCallCancellation current = CURRENT.get();
        checkpoint();
        try {
            return action.get();
        } finally {
            if (current != null) {
                current.clearCall();
            }
        }
    }

    public static OpenAiHttpClientBuilderCustomizer customizer() {
        return builder -> builder.interceptor(chain -> {
            ModelCallCancellation current = CURRENT.get();
            if (current != null) {
                current.register(chain.call());
            }
            return chain.proceed(chain.request());
        });
    }

    private synchronized void register(Call candidate) {
        call = candidate;
        if (cancelled) {
            candidate.cancel();
        }
    }

    private synchronized void clearCall() {
        // 同步模型调用返回前仍可能读取响应体，不能在拦截器返回时清理。
        call = null;
    }
}
