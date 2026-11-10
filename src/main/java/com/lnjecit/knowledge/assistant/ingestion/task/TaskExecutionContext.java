package com.lnjecit.knowledge.assistant.ingestion.task;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

public final class TaskExecutionContext {
    private final long started;
    private final long budget;
    private final LongSupplier ticker;
    private final Instant deadline;

    public TaskExecutionContext(Duration duration) {
        this(duration, System::nanoTime);
    }

    public TaskExecutionContext(Duration duration, LongSupplier ticker) {
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("Invalid task duration");
        this.ticker = ticker;
        this.started = ticker.getAsLong();
        this.budget = duration.toNanos();
        this.deadline = Instant.now().plus(duration);
    }

    public Instant deadline() {
        return deadline;
    }

    // 剩余时长只依赖单调时钟，墙上时钟变化不影响超时判断。
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, budget - (ticker.getAsLong() - started)));
    }

    public void checkTimeout() {
        if (remaining().isZero()) throw new TaskExecutionException(TaskExecutionException.Code.TASK_TIMEOUT);
    }
}
