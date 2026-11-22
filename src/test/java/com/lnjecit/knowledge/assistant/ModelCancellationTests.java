package com.lnjecit.knowledge.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelCallCancellation;
import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.ForwardingSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;

class ModelCancellationTests {
    private static final int WAIT_SECONDS = 10;
    private static final int RESPONSE_DELAY_MS = 1500;
    private static final String REQUEST_ID_HEADER = "X-Local-Test-Request-Id";
    private static final int CANCEL_WAIT_MS = 2000;
    private static final String RESPONSE =
            """
            {"id":"local","object":"chat.completion","created":1,"model":"local",
             "choices":[{"index":0,"message":{"role":"assistant","content":"local ok"},"finish_reason":"stop"}]}
            """;

    @Test
    void normalCompletion() throws Exception {
        completedCall(false);
    }

    @Test
    void cancellationAfterCompletion() throws Exception {
        completedCall(true);
    }

    private void completedCall(boolean cancelAfterCompletion) throws Exception {
        try (LocalServer server = new LocalServer(false)) {
            ModelCall call = new ModelCall(server.model());
            Thread worker = startWorker(call.task);
            assertEquals("local ok", text(call.task.get(WAIT_SECONDS, TimeUnit.SECONDS)));
            join(worker);
            boolean accepted = cancelAfterCompletion && call.task.cancel(true);

            assertFalse(accepted);
            assertFalse(call.task.isCancelled());
            assertTrue(call.finallyAt.get() > 0);
            assertEquals("local ok", text(call.task.get(WAIT_SECONDS, TimeUnit.SECONDS)));
            assertEquals("RESPONSE_SENT", server.first.result.get(WAIT_SECONDS, TimeUnit.SECONDS));
            server.assertNoAdditionalConnections();
            assertEquals(1, server.first.requests.get());
            System.out.printf(
                    "%s accepted=%s cancelled=%s worker=%s HTTP=RESPONSE_SENT requests=%d%n",
                    cancelAfterCompletion ? "completed" : "normal",
                    accepted,
                    call.task.isCancelled(),
                    worker.getState(),
                    server.first.requests.get());
        }
    }

    @Test
    void cancellationWhileWaitingForHttp() throws Exception {
        try (LocalServer server = new LocalServer(true)) {
            ModelCall call = new ModelCall(server.model());
            Thread worker = startWorker(call.task);
            assertTrue(server.first.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            long cancelledAt = System.nanoTime();
            boolean accepted = call.task.cancel(true);
            assertTrue(accepted);
            assertTrue(call.task.isCancelled());
            assertThrows(CancellationException.class, () -> call.task.get(WAIT_SECONDS, TimeUnit.SECONDS));

            // 用 Callable 的 finally 和线程 join 分别记录调用结束与线程终止。
            Thread.State afterCancel = worker.getState();
            server.first.observeConnection.countDown();
            join(worker);
            long workerExitedBy = System.nanoTime();
            String network = server.first.result.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(call.finallyAt.get() >= cancelledAt);
            server.assertNoAdditionalConnections();
            assertEquals(1, server.first.requests.get());
            System.out.printf(
                    "in-flight accepted=%s cancelled=%s stateAfterCancel=%s finallyMs=%d worker=%s "
                            + "workerExitByMs=%d outcome=%s HTTP=%s networkMs=%d requests=%d%n",
                    accepted,
                    call.task.isCancelled(),
                    afterCancel,
                    millisSince(cancelledAt, call.finallyAt.get()),
                    worker.getState(),
                    millisSince(cancelledAt, workerExitedBy),
                    call.outcome.get(),
                    network,
                    millisSince(cancelledAt, server.first.networkAt.get()),
                    server.first.requests.get());
        }
    }

    @Test
    void cancellationWhileQueued() throws Exception {
        try (LocalServer server = new LocalServer(false)) {
            ModelCall call = new ModelCall(server.model());
            CountDownLatch occupied = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    1,
                    1,
                    0L,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    Thread.ofPlatform().daemon().factory());
            try {
                FutureTask<Boolean> blocker = new FutureTask<>(() -> {
                    occupied.countDown();
                    return release.await(WAIT_SECONDS, TimeUnit.SECONDS);
                });
                executor.execute(blocker);
                assertTrue(occupied.await(WAIT_SECONDS, TimeUnit.SECONDS));
                executor.execute(call.task);
                assertTrue(executor.getQueue().contains(call.task));
                boolean accepted = call.task.cancel(true);
                assertTrue(accepted);
                assertTrue(call.task.isCancelled());
                release.countDown();
                assertTrue(blocker.get(WAIT_SECONDS, TimeUnit.SECONDS));
                executor.shutdown();
                assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
                assertEquals(2, executor.getCompletedTaskCount());
                assertEquals("NOT_STARTED", call.outcome.get());
                assertEquals(0, call.finallyAt.get());
                assertEquals(0, server.first.requests.get());
                System.out.printf(
                        "queued accepted=%s cancelled=%s callable=%s requests=%d%n",
                        accepted, call.task.isCancelled(), call.outcome.get(), server.first.requests.get());
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void sdkTimeoutWithoutCancellation() throws Exception {
        try (LocalServer server = new LocalServer(false)) {
            ModelCall call = new ModelCall(server.model(1));
            Thread worker = startWorker(call.task);
            assertTrue(server.first.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> call.task.get(WAIT_SECONDS, TimeUnit.SECONDS));
            join(worker);
            assertFalse(call.task.isCancelled());
            assertTrue(call.finallyAt.get() > 0);
            String network = server.first.result.get(WAIT_SECONDS, TimeUnit.SECONDS);
            server.assertNoAdditionalConnections();
            assertEquals(1, server.first.requests.get());
            System.out.printf(
                    "timeout accepted=false cancelled=%s finallyFromArrivalMs=%d worker=%s "
                            + "outcome=%s HTTP=%s networkFromArrivalMs=%d requests=%d%n",
                    call.task.isCancelled(),
                    millisSince(server.first.arrivedAt.get(), call.finallyAt.get()),
                    worker.getState(),
                    failureTypes(failure.getCause()),
                    network,
                    millisSince(server.first.arrivedAt.get(), server.first.networkAt.get()),
                    server.first.requests.get());
        }
    }

    @Test
    void cancelsOnlyTheTargetHttpCall() throws Exception {
        Map<String, Call> calls = new ConcurrentHashMap<>();
        try (LocalServer server = new LocalServer(true, "A")) {
            ModelCallCancellation cancellationA = new ModelCallCancellation();
            ModelCallCancellation cancellationB = new ModelCallCancellation();
            ChatModel model = server.customizedModel(6, builder -> {
                ModelCallCancellation.customizer().customize(builder);
                trackCalls(calls).customize(builder);
            });
            ModelCall a = new ModelCall(model, identifiedPrompt("A"), cancellationA);
            Thread workerA = startWorker(a.task);
            assertTrue(server.first.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));

            Exchange second = server.startExchange(true, "B");
            ModelCall b = new ModelCall(model, identifiedPrompt("B"), cancellationB);
            Thread workerB = startWorker(b.task);
            assertTrue(second.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(2, calls.size());
            Call target = calls.get("A");
            Call other = calls.get("B");
            assertNotNull(target);
            assertNotNull(other);
            assertFalse(target.isCanceled());
            assertFalse(other.isCanceled());

            // 测试线程只取消 A 的 HTTP 句柄，两条请求均已到达服务端。
            long cancelledAt = System.nanoTime();
            cancellationA.cancel();
            server.first.observeConnection.countDown();
            assertEquals("PEER_EOF", server.first.result.get(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS));
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> a.task.get(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS));
            join(workerA);
            long workerExitMs = millisSince(cancelledAt, System.nanoTime());
            long eofMs = millisSince(cancelledAt, server.first.networkAt.get());
            assertTrue(eofMs <= CANCEL_WAIT_MS);
            assertTrue(workerExitMs <= CANCEL_WAIT_MS);
            assertTrue(a.finallyAt.get() >= cancelledAt);
            assertFalse(a.task.isCancelled());
            assertEquals(Map.of("B", other), calls);
            assertFalse(other.isCanceled());
            assertFalse(b.task.isDone());
            System.out.printf(
                    "target=A HTTP=PEER_EOF eofMs=%d worker=%s workerExitByMs=%d outcome=%s%n",
                    eofMs, workerA.getState(), workerExitMs, failureTypes(failure.getCause()));

            second.observeConnection.countDown();
            assertEquals("local ok", text(b.task.get(WAIT_SECONDS, TimeUnit.SECONDS)));
            join(workerB);
            assertEquals("RESPONSE_SENT", second.result.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(calls.isEmpty());
            server.assertNoAdditionalConnections();
            System.out.printf(
                    "target=B HTTP=RESPONSE_SENT worker=%s remainingHandles=%d%n", workerB.getState(), calls.size());
        }
    }

    @Test
    void clearsHttpCallAfterNetworkTimeout() throws Exception {
        Map<String, Call> calls = new ConcurrentHashMap<>();
        try (LocalServer server = new LocalServer(true, "timeout")) {
            ModelCall call = new ModelCall(server.customizedModel(1, trackCalls(calls)), identifiedPrompt("timeout"));
            Thread worker = startWorker(call.task);
            assertTrue(server.first.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            assertNotNull(calls.get("timeout"));
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> call.task.get(WAIT_SECONDS, TimeUnit.SECONDS));
            join(worker);
            assertFalse(call.task.isCancelled());
            assertTrue(failureTypes(failure.getCause()).contains("SocketTimeoutException"));
            assertTrue(calls.isEmpty());
            server.first.observeConnection.countDown();
            assertEquals("PEER_EOF", server.first.result.get(WAIT_SECONDS, TimeUnit.SECONDS));
            server.assertNoAdditionalConnections();
            System.out.printf(
                    "target=timeout HTTP=PEER_EOF worker=%s outcome=%s remainingHandles=%d%n",
                    worker.getState(), failureTypes(failure.getCause()), calls.size());
        }
    }

    @Test
    void productionCancellationBeforeCallRegistration() throws Exception {
        ModelCallCancellation cancellation = new ModelCallCancellation();
        CountDownLatch beforeRegistration = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (LocalServer server = new LocalServer(true)) {
            ChatModel model = server.customizedModel(15, builder -> {
                builder.interceptor(chain -> {
                    beforeRegistration.countDown();
                    try {
                        assertTrue(proceed.await(WAIT_SECONDS, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException(interrupted);
                    }
                    return chain.proceed(chain.request());
                });
                ModelCallCancellation.customizer().customize(builder);
            });
            FutureTask<ChatResponse> task = cancellableCall(cancellation, model);
            Thread worker = startWorker(task);
            try {
                assertTrue(beforeRegistration.await(WAIT_SECONDS, TimeUnit.SECONDS));
                cancellation.cancel();
            } finally {
                proceed.countDown();
            }
            assertThrows(ExecutionException.class, () -> task.get(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS));
            join(worker);
            assertEquals(1, server.first.arrived.getCount());
        }
    }

    @Test
    void productionCancellationWhileReadingResponseBody() throws Exception {
        assertProductionCancellation(true);
    }

    @Test
    void productionCancellationWhileWaitingForHeaders() throws Exception {
        assertProductionCancellation(false);
    }

    private void assertProductionCancellation(boolean partialBody) throws Exception {
        ModelCallCancellation cancellation = new ModelCallCancellation();
        try (LocalServer server = new LocalServer(true, null, partialBody)) {
            CountDownLatch bodyRead = new CountDownLatch(1);
            ChatModel model = partialBody
                    ? server.customizedModel(15, builder -> {
                        ModelCallCancellation.customizer().customize(builder);
                        builder.interceptor(chain -> {
                            Response response = chain.proceed(chain.request());
                            ResponseBody original = response.body();
                            ForwardingSource source = new ForwardingSource(original.source()) {
                                @Override
                                public long read(Buffer sink, long byteCount) throws IOException {
                                    bodyRead.countDown();
                                    return super.read(sink, byteCount);
                                }
                            };
                            return response.newBuilder()
                                    .body(ResponseBody.create(
                                            original.contentType(), original.contentLength(), Okio.buffer(source)))
                                    .build();
                        });
                    })
                    : server.model(15);
            FutureTask<ChatResponse> task = cancellableCall(cancellation, model);
            Thread worker = startWorker(task);
            assertTrue(server.first.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            if (partialBody) {
                assertTrue(bodyRead.await(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            assertFalse(task.isDone());
            cancellation.cancel();
            assertThrows(ExecutionException.class, () -> task.get(CANCEL_WAIT_MS, TimeUnit.MILLISECONDS));
            join(worker);
            server.first.observeConnection.countDown();
            assertEquals("PEER_EOF", server.first.result.get(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    private static FutureTask<ChatResponse> cancellableCall(ModelCallCancellation cancellation, ChatModel model) {
        return new FutureTask<>(() -> cancellation.run(
                () -> ModelCallCancellation.duringModelCall(() -> model.call(new Prompt("local test")))));
    }

    private static Prompt identifiedPrompt(String requestId) {
        return new Prompt(
                "local test",
                OpenAiChatOptions.builder()
                        .customHeaders(Map.of(REQUEST_ID_HEADER, requestId))
                        .build());
    }

    private static OpenAiHttpClientBuilderCustomizer trackCalls(Map<String, Call> calls) {
        return builder -> builder.interceptor(chain -> {
            String requestId = chain.request().header(REQUEST_ID_HEADER);
            assertNotNull(requestId);
            Call call = chain.call();
            // 仅在 HTTP 请求执行期间保留句柄，成功和异常共用清理路径。
            assertNull(calls.putIfAbsent(requestId, call));
            try {
                return chain.proceed(chain.request());
            } finally {
                calls.remove(requestId, call);
            }
        });
    }

    private static String failureTypes(Throwable failure) {
        StringBuilder types = new StringBuilder(failure.getClass().getSimpleName());
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            types.append(" / ").append(cause.getClass().getSimpleName());
        }
        return types.toString();
    }

    private static String text(ChatResponse response) {
        return response.getResult().getOutput().getText();
    }

    private static Thread startWorker(FutureTask<?> task) {
        return Thread.ofPlatform().daemon().name("model-cancellation-test").start(task);
    }

    private static void join(Thread worker) throws InterruptedException {
        worker.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        assertEquals(Thread.State.TERMINATED, worker.getState());
    }

    private static long millisSince(long start, long end) {
        return TimeUnit.NANOSECONDS.toMillis(end - start);
    }

    private static final class ModelCall {
        private final AtomicLong finallyAt = new AtomicLong();
        private final AtomicReference<String> outcome = new AtomicReference<>("NOT_STARTED");
        private final FutureTask<ChatResponse> task;

        private ModelCall(ChatModel model) {
            this(model, new Prompt("local test"));
        }

        private ModelCall(ChatModel model, Prompt prompt) {
            this(model, prompt, null);
        }

        private ModelCall(ChatModel model, Prompt prompt, ModelCallCancellation cancellation) {
            task = new FutureTask<>(() -> {
                outcome.set("RUNNING");
                try {
                    ChatResponse response = cancellation == null
                            ? model.call(prompt)
                            : cancellation.run(() -> ModelCallCancellation.duringModelCall(() -> model.call(prompt)));
                    outcome.set("RETURNED");
                    return response;
                } catch (RuntimeException failure) {
                    outcome.set(failureTypes(failure));
                    throw failure;
                } finally {
                    finallyAt.set(System.nanoTime());
                }
            });
        }
    }

    private static final class LocalServer implements AutoCloseable {
        private final ServerSocket listener;
        private final Exchange first;
        private Exchange second;

        private LocalServer(boolean holdResponse) throws IOException {
            this(holdResponse, null);
        }

        private LocalServer(boolean holdResponse, String requestId) throws IOException {
            this(holdResponse, requestId, false);
        }

        private LocalServer(boolean holdResponse, String requestId, boolean partialBody) throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(WAIT_SECONDS * 1000);
            first = new Exchange(listener, holdResponse, requestId, partialBody);
        }

        private Exchange startExchange(boolean holdResponse, String requestId) {
            second = new Exchange(listener, holdResponse, requestId, false);
            return second;
        }

        private ChatModel model() {
            return model(6);
        }

        private ChatModel model(int timeoutSeconds) {
            return new ModelConfiguration().chatModel(properties(timeoutSeconds));
        }

        private ChatModel customizedModel(int timeoutSeconds, OpenAiHttpClientBuilderCustomizer customizer) {
            ModelProperties properties = properties(timeoutSeconds);
            return OpenAiChatModel.builder()
                    .options(OpenAiChatOptions.builder()
                            .apiKey(properties.getApiKey())
                            .baseUrl(properties.getBaseUrl())
                            .model(properties.getChatModel())
                            .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                            .maxRetries(0)
                            .maxTokens(128)
                            .build())
                    .httpClientBuilderCustomizer(customizer)
                    .build();
        }

        private ModelProperties properties(int timeoutSeconds) {
            ModelProperties properties = new ModelProperties();
            properties.setEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + listener.getLocalPort() + "/v1");
            properties.setApiKey("local-test-key");
            properties.setChatModel("local");
            properties.setTimeoutSeconds(timeoutSeconds);
            return properties;
        }

        private void assertNoAdditionalConnections() throws IOException {
            listener.setSoTimeout(150);
            assertThrows(SocketTimeoutException.class, () -> {
                try (Socket unexpected = listener.accept()) {
                    throw new AssertionError("Unexpected connection: " + unexpected.getRemoteSocketAddress());
                }
            });
        }

        @Override
        public void close() throws IOException, InterruptedException {
            listener.close();
            first.observeConnection.countDown();
            if (second != null) {
                second.observeConnection.countDown();
                join(second.thread);
            }
            join(first.thread);
        }
    }

    private static final class Exchange {
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch observeConnection = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicLong arrivedAt = new AtomicLong();
        private final AtomicLong networkAt = new AtomicLong();
        private final FutureTask<String> result;
        private final Thread thread;

        private Exchange(ServerSocket listener, boolean holdResponse, String requestId, boolean partialBody) {
            result = new FutureTask<>(() -> serve(listener, holdResponse, requestId, partialBody));
            thread = Thread.ofPlatform().daemon().name("local-chat-http").start(result);
        }

        private String serve(ServerSocket listener, boolean holdResponse, String requestId, boolean partialBody)
                throws Exception {
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(WAIT_SECONDS * 1000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                assertEquals("POST /v1/chat/completions HTTP/1.1", readLine(input));
                requests.incrementAndGet();
                int length = -1;
                String receivedId = null;
                for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("content-length:")) {
                        length = Integer.parseInt(
                                line.substring(line.indexOf(':') + 1).trim());
                    } else if (lower.startsWith(REQUEST_ID_HEADER.toLowerCase(Locale.ROOT) + ":")) {
                        receivedId = line.substring(line.indexOf(':') + 1).trim();
                    }
                }
                assertTrue(length > 0 && length <= 65536);
                assertEquals(length, input.readNBytes(length).length);
                assertEquals(requestId, receivedId);
                arrivedAt.set(System.nanoTime());
                arrived.countDown();
                if (partialBody) {
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + RESPONSE.getBytes(StandardCharsets.UTF_8).length + "\r\nConnection: close\r\n\r\n{";
                    socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                }
                if (holdResponse) {
                    assertTrue(observeConnection.await(WAIT_SECONDS, TimeUnit.SECONDS));
                }
                // 响应前读取对端 EOF。观察窗口结束后才发送响应。
                socket.setSoTimeout(RESPONSE_DELAY_MS);
                try {
                    int next = input.read();
                    networkAt.set(System.nanoTime());
                    return next == -1 ? "PEER_EOF" : "UNKNOWN_UNEXPECTED_DATA";
                } catch (SocketTimeoutException elapsed) {
                    networkAt.set(System.nanoTime());
                    byte[] body = RESPONSE.getBytes(StandardCharsets.UTF_8);
                    String headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                            + body.length + "\r\nConnection: close\r\n\r\n";
                    try {
                        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(body);
                        return "RESPONSE_SENT";
                    } catch (IOException writeFailure) {
                        return "UNKNOWN_WRITE_FAILED";
                    }
                } catch (IOException readFailure) {
                    networkAt.set(System.nanoTime());
                    return "UNKNOWN_READ_FAILED";
                }
            }
        }

        private static String readLine(BufferedInputStream input) throws IOException {
            StringBuilder line = new StringBuilder();
            for (int value = input.read(); value != '\n'; value = input.read()) {
                if (value < 0 || line.length() >= 8192) {
                    throw new IOException("Incomplete or oversized HTTP header");
                }
                if (value != '\r') {
                    line.append((char) value);
                }
            }
            return line.toString();
        }
    }
}
