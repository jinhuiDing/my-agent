package com.example.agent.agent;

import java.util.List;

public record AgentRunResult(String question, String answer, List<Step> steps, boolean solved, String stopReason) {

    /** 把每一轮的过程打出来，学习时最需要看这个。 */
    public String describeTrace() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            sb.append(steps.get(i).toPromptBlock(i + 1)).append('\n');
        }
        return sb.toString();
    }
}
