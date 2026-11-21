package com.example.agent.runtime;

import com.example.agent.agent.Step;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AgentRunContext {
    private final String question;
    private final List<Step> history = new ArrayList<>();
    private int currentStep;
    private final int maxSteps;
    private final Instant startTime;
    private AgentRunStatus status = AgentRunStatus.CREATED;

    public AgentRunContext(String question, int maxSteps) {
        this.question = question;
        this.maxSteps = maxSteps;
        this.startTime = Instant.now();
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


    public void start() {
        if (status != AgentRunStatus.CREATED) {
            throw new IllegalStateException(
                    "Agent Run cannot start from status: " + status
            );
        }

        status = AgentRunStatus.RUNNING;

    }

    public void complete() {
        if (status != AgentRunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Agent Run cannot complete from status: " + status
            );
        }

        status = AgentRunStatus.COMPLETED;

    }

    public void fail() {

        if (status != AgentRunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Agent Run cannot failed from status: " + status
            );
        }

        status = AgentRunStatus.FAILED;

    }

    public void finishByLimit() {
        // 达到最大步数
        if (status != AgentRunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Agent Run cannot max_steps_reached from status: " + status
            );
        }

        status = AgentRunStatus.MAX_STEPS_REACHED;
    }

    public AgentRunStatus getStatus() {
        return status;
    }
}
