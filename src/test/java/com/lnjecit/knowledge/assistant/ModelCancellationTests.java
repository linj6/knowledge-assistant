package com.lnjecit.knowledge.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lnjecit.knowledge.assistant.infrastructure.ai.ModelConfiguration;
import com.lnjecit.knowledge.assistant.infrastructure.config.ModelProperties;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

class ModelCancellationTests {
    private static final int WAIT_SECONDS = 10;
    private static final int RESPONSE_DELAY_MS = 1500;
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
            assertEquals("RESPONSE_SENT", server.firstExchange.get(WAIT_SECONDS, TimeUnit.SECONDS));
            server.assertNoAdditionalConnections();
            assertEquals(1, server.requests.get());
            System.out.printf(
                    "%s accepted=%s cancelled=%s worker=%s HTTP=RESPONSE_SENT requests=%d%n",
                    cancelAfterCompletion ? "completed" : "normal",
                    accepted,
                    call.task.isCancelled(),
                    worker.getState(),
                    server.requests.get());
        }
    }

    @Test
    void cancellationWhileWaitingForHttp() throws Exception {
        try (LocalServer server = new LocalServer(true)) {
            ModelCall call = new ModelCall(server.model());
            Thread worker = startWorker(call.task);
            assertTrue(server.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            long cancelledAt = System.nanoTime();
            boolean accepted = call.task.cancel(true);
            assertTrue(accepted);
            assertTrue(call.task.isCancelled());
            assertThrows(CancellationException.class, () -> call.task.get(WAIT_SECONDS, TimeUnit.SECONDS));

            // 用 Callable 的 finally 和线程 join 分别记录调用结束与线程终止。
            Thread.State afterCancel = worker.getState();
            server.observeConnection.countDown();
            join(worker);
            long workerExitedBy = System.nanoTime();
            String network = server.firstExchange.get(WAIT_SECONDS, TimeUnit.SECONDS);
            assertTrue(call.finallyAt.get() >= cancelledAt);
            server.assertNoAdditionalConnections();
            assertEquals(1, server.requests.get());
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
                    millisSince(cancelledAt, server.networkAt.get()),
                    server.requests.get());
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
                assertEquals(0, server.requests.get());
                System.out.printf(
                        "queued accepted=%s cancelled=%s callable=%s requests=%d%n",
                        accepted, call.task.isCancelled(), call.outcome.get(), server.requests.get());
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
            assertTrue(server.arrived.await(WAIT_SECONDS, TimeUnit.SECONDS));
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> call.task.get(WAIT_SECONDS, TimeUnit.SECONDS));
            join(worker);
            assertFalse(call.task.isCancelled());
            assertTrue(call.finallyAt.get() > 0);
            String network = server.firstExchange.get(WAIT_SECONDS, TimeUnit.SECONDS);
            server.assertNoAdditionalConnections();
            assertEquals(1, server.requests.get());
            System.out.printf(
                    "timeout accepted=false cancelled=%s finallyFromArrivalMs=%d worker=%s "
                            + "outcome=%s HTTP=%s networkFromArrivalMs=%d requests=%d%n",
                    call.task.isCancelled(),
                    millisSince(server.arrivedAt.get(), call.finallyAt.get()),
                    worker.getState(),
                    failureTypes(failure.getCause()),
                    network,
                    millisSince(server.arrivedAt.get(), server.networkAt.get()),
                    server.requests.get());
        }
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
            task = new FutureTask<>(() -> {
                outcome.set("RUNNING");
                try {
                    ChatResponse response = model.call(new Prompt("local test"));
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
        private final CountDownLatch arrived = new CountDownLatch(1);
        private final CountDownLatch observeConnection = new CountDownLatch(1);
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicLong arrivedAt = new AtomicLong();
        private final AtomicLong networkAt = new AtomicLong();
        private final ServerSocket listener;
        private final boolean holdResponse;
        private final FutureTask<String> firstExchange;
        private final Thread serverThread;

        private LocalServer(boolean holdResponse) throws IOException {
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            this.holdResponse = holdResponse;
            listener.setSoTimeout(WAIT_SECONDS * 1000);
            firstExchange = new FutureTask<>(this::serve);
            serverThread = Thread.ofPlatform().daemon().name("local-chat-http").start(firstExchange);
        }

        private ChatModel model() {
            return model(6);
        }

        private ChatModel model(int timeoutSeconds) {
            ModelProperties properties = new ModelProperties();
            properties.setEnabled(true);
            properties.setBaseUrl("http://127.0.0.1:" + listener.getLocalPort() + "/v1");
            properties.setApiKey("local-test-key");
            properties.setChatModel("local");
            properties.setTimeoutSeconds(timeoutSeconds);
            return new ModelConfiguration().chatModel(properties);
        }

        private String serve() throws Exception {
            try (Socket socket = listener.accept()) {
                socket.setSoTimeout(WAIT_SECONDS * 1000);
                BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
                assertEquals("POST /v1/chat/completions HTTP/1.1", readLine(input));
                requests.incrementAndGet();
                int length = -1;
                for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) {
                    if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                        length = Integer.parseInt(
                                line.substring(line.indexOf(':') + 1).trim());
                    }
                }
                assertTrue(length > 0 && length <= 65536);
                assertEquals(length, input.readNBytes(length).length);
                arrivedAt.set(System.nanoTime());
                arrived.countDown();
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

        private void assertNoAdditionalConnections() throws IOException {
            listener.setSoTimeout(150);
            assertThrows(SocketTimeoutException.class, () -> {
                try (Socket unexpected = listener.accept()) {
                    throw new AssertionError("Unexpected connection: " + unexpected.getRemoteSocketAddress());
                }
            });
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

        @Override
        public void close() throws IOException, InterruptedException {
            listener.close();
            join(serverThread);
        }
    }
}
