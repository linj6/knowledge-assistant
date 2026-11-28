package com.lnjecit.knowledge.assistant.ingestion.git;

import com.lnjecit.knowledge.assistant.common.RequestIdContext;
import com.lnjecit.knowledge.assistant.common.error.BusinessException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class GitCommandRunner {
    private static final Logger LOG = LoggerFactory.getLogger(GitCommandRunner.class);

    enum Command {
        ROOT("rev-parse", "--show-toplevel"),
        HEAD("rev-parse", "--verify", "HEAD^{commit}"),
        BRANCH("rev-parse", "--symbolic-full-name", "HEAD"),
        TREE("ls-tree", "-z", "-l"),
        RECURSIVE_TREE("ls-tree", "-z", "-l", "-r", "-t"),
        BLOB("cat-file", "blob");

        private final List<String> arguments;

        Command(String... arguments) {
            this.arguments = List.of(arguments);
        }
    }

    private final GitRepositoryProperties properties;

    public GitCommandRunner(GitRepositoryProperties properties) {
        this.properties = properties;
    }

    byte[] run(Path repository, Command command) {
        return execute(repository, command, List.of());
    }

    byte[] run(Path repository, Command command, String oid) {
        if (!oid.matches("[0-9a-f]{40}|[0-9a-f]{64}")) {
            throw new BusinessException(GitErrorCode.REPOSITORY_UNREADABLE);
        }
        return execute(repository, command, List.of(oid));
    }

    private byte[] execute(Path repository, Command command, List<String> arguments) {
        List<String> invocation = new ArrayList<>(
                List.of(properties.getExecutable(), "--no-pager", "--no-replace-objects", "-C", repository.toString()));
        invocation.addAll(command.arguments);
        invocation.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(invocation);
        Map<String, String> environment = builder.environment();
        // 清除继承的 Git 路径和配置覆盖，并阻断所有远程传输协议。
        environment.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("GIT_"));
        environment.put("GIT_TERMINAL_PROMPT", "0");
        environment.put("GIT_NO_LAZY_FETCH", "1");
        environment.put("GIT_ALLOW_PROTOCOL", "");
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", nullDevice());
        environment.put("GIT_OPTIONAL_LOCKS", "0");

        long started = System.nanoTime();
        long timeout = properties.getCommandTimeout().toNanos();
        Process process;
        try {
            process = builder.start();
        } catch (IOException ex) {
            throw failure(command, GitErrorCode.GIT_UNAVAILABLE, null, started);
        }

        AtomicLong remaining = new AtomicLong(properties.getMaxOutputBytes());
        try (InputStream stdout = process.getInputStream();
                InputStream stderr = process.getErrorStream()) {
            process.getOutputStream().close();
            FutureTask<byte[]> output = new FutureTask<>(() -> drain(stdout, remaining, process, true));
            FutureTask<byte[]> errors = new FutureTask<>(() -> drain(stderr, remaining, process, false));
            Thread.startVirtualThread(output);
            Thread.startVirtualThread(errors);
            try {
                byte[] result = output.get(timeLeft(started, timeout), TimeUnit.NANOSECONDS);
                errors.get(timeLeft(started, timeout), TimeUnit.NANOSECONDS);
                if (!process.waitFor(timeLeft(started, timeout), TimeUnit.NANOSECONDS)) {
                    throw new TimeoutException();
                }
                int exit = process.exitValue();
                if (remaining.get() < 0) throw failure(command, GitErrorCode.OUTPUT_LIMIT, exit, started);
                if (exit != 0) throw failure(command, GitErrorCode.REPOSITORY_UNREADABLE, exit, started);
                LOG.debug("gitCommand={} exitCode={} elapsedMs={}", command, exit, elapsedMillis(started));
                return result;
            } finally {
                terminate(process);
                output.cancel(true);
                errors.cancel(true);
            }
        } catch (TimeoutException ex) {
            GitErrorCode error = remaining.get() < 0 ? GitErrorCode.OUTPUT_LIMIT : GitErrorCode.COMMAND_TIMEOUT;
            throw failure(command, error, process.isAlive() ? null : process.exitValue(), started);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure(command, GitErrorCode.REPOSITORY_UNREADABLE, null, started);
        } catch (ExecutionException | IOException ex) {
            GitErrorCode error = remaining.get() < 0 ? GitErrorCode.OUTPUT_LIMIT : GitErrorCode.REPOSITORY_UNREADABLE;
            throw failure(command, error, process.isAlive() ? null : process.exitValue(), started);
        } finally {
            terminate(process);
        }
    }

    private static String nullDevice() {
        return System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
    }

    private static byte[] drain(InputStream stream, AtomicLong remaining, Process process, boolean capture)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (remaining.addAndGet(-count) < 0) {
                terminate(process);
                break;
            }
            if (capture) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void terminate(Process process) {
        if (process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    private static long timeLeft(long started, long timeout) {
        return Math.max(0, timeout - (System.nanoTime() - started));
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private static BusinessException failure(Command command, GitErrorCode error, Integer exit, long started) {
        LOG.warn(
                "requestId={} gitCommand={} error={} exitCode={} elapsedMs={}",
                RequestIdContext.current(),
                command,
                error,
                exit,
                elapsedMillis(started));
        return new BusinessException(error);
    }
}
