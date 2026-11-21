package com.gene.agent.agent;

/**
 * 一轮 "思考-行动-观察"。history 就是这个 record 的列表，拼 prompt 时按顺序渲染。
 */
public record Step(String thought, String action, String actionInput, String observation) {

    String toPromptBlock(int index) {
        StringBuilder sb = new StringBuilder();
        sb.append("步骤 ").append(index).append('\n');
        if (thought != null && !thought.isBlank()) {
            sb.append("Thought: ").append(thought).append('\n');
        }
        if (action != null) {
            sb.append("Action: ").append(action).append('\n');
            sb.append("Action Input: ").append(actionInput == null ? "" : actionInput).append('\n');
        }
        sb.append("Observation: ").append(observation == null ? "" : observation);
        return sb.toString();
    }
}
