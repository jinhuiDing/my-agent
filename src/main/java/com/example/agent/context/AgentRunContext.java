package com.example.agent.context;

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

    public AgentRunContext(String question, int maxSteps) {
        this.question = question;
        this.maxSteps = maxSteps;
        this.startTime = Instant.now();
    }

    public String getQuestion() {
        return question;
    }

    public List<Step> getHistory() {
        return history;
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

     public List<Step> history() {
         return Collections.unmodifiableList(history);
     }
}
