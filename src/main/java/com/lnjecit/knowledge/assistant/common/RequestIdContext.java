package com.lnjecit.knowledge.assistant.common;

import org.slf4j.MDC;

public final class RequestIdContext {
    private static final String KEY = "requestId";

    private RequestIdContext() {}

    public static String current() {
        return MDC.get(KEY);
    }

    public static void set(String id) {
        MDC.put(KEY, id);
    }

    public static void clear() {
        MDC.remove(KEY);
    }
}
