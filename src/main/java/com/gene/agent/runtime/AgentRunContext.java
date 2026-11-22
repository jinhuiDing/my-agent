package com.gene.agent.runtime;

import com.gene.agent.agent.Step;
import com.gene.agent.core.AgentRunTerminationReason;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class AgentRunContext {
    private final String runId;
    private final String question;
    private final List<Step> history = new ArrayList<>();
    private int currentStep;
    private final int maxSteps;
    private  Instant startTime;
    private AgentRunStatus status = AgentRunStatus.CREATED;
    private AgentRunTerminationReason terminationReason;


    private Instant finishedAt;

    private long startNanos;

    private long finishNanos;
    public AgentRunContext(String question, int maxSteps) {
        this(UUID.randomUUID().toString().replace("-", "").substring(0, 12), question, maxSteps);
    }

    public AgentRunContext(String runId, String question, int maxSteps) {
        this.runId = runId;
        this.question = question;
        this.maxSteps = maxSteps;
    }

    public String getRunId() {
        return runId;
    }

    public String getQuestion() {
        return question;
    }

    public List<Step> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    public int getCurrentStep() {
        return currentStep;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public int nextStep() {
        if (currentStep >= maxSteps) {
            throw new IllegalStateException("已达到最大执行步数");
        }

        return ++currentStep;
    }

    public void addStep(Step step) {
        history.add(step);
    }


    public synchronized void start() {
        if (status != AgentRunStatus.CREATED) {
            throw new IllegalStateException(
                    "Agent Run cannot start from status: " + status
            );
        }

        status = AgentRunStatus.RUNNING;
        startTime = Instant.now();
        startNanos = System.nanoTime();
    }

    public void complete() {
        if (status != AgentRunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Agent Run cannot complete from status: " + status
            );
        }
        terminate(
                AgentRunStatus.COMPLETED,
                AgentRunTerminationReason.FINAL_ANSWER
        );

    }

    public void fail(AgentRunTerminationReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("终止原因不能为空");
        }

        terminate(AgentRunStatus.FAILED, reason);
    }

    public void finishByLimit() {
        // 达到最大步数
        terminate(
                AgentRunStatus.MAX_STEPS_REACHED,
                AgentRunTerminationReason.STEP_LIMIT_REACHED
        );
    }
    public void cancel() {
        terminate(
                AgentRunStatus.CANCELLED,
                AgentRunTerminationReason.USER_CANCEL
        );
    }
    public synchronized  AgentRunStatus getStatus() {
        return status;
    }

    public AgentRunTerminationReason getTerminationReason() {
        return terminationReason;
    }



    public Instant getFinishedAt() {
        return finishedAt;
    }

    public synchronized  boolean isTerminal() {
        return switch (status) {
            case COMPLETED, FAILED, CANCELLED, MAX_STEPS_REACHED -> true;
            default -> false;
        };
    }
    private synchronized void terminate(
            AgentRunStatus targetStatus,
            AgentRunTerminationReason reason
    ) {
        if (status != AgentRunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Run 只能从 RUNNING 状态终止，当前状态：" + status
            );
        }

        status = targetStatus;
        terminationReason = reason;
        finishedAt = Instant.now();
        finishNanos = System.nanoTime();
    }


    public long getDurationMs() {
        if (startNanos == 0) {
            return 0;
        }

        long end = finishNanos == 0
                ? System.nanoTime()
                : finishNanos;

        return (end - startNanos) / 1_000_000;
    }
}
