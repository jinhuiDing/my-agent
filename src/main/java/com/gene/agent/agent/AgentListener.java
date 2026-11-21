package com.gene.agent.agent;

import java.util.List;

/**
 * agent 运行过程中的实时事件出口。
 * CLI 用它逐字打印，HTTP 流式端点用它推 SSE；不实现任何方法就是静默运行。
 */
public interface AgentListener {

    default void onRunStart(String question, List<String> tools, int maxSteps) {
    }

    default void onStepStart(int step, int maxSteps) {
    }

    /** 模型流式输出的增量文本。 */
    default void onModelDelta(String delta) {
    }

    /** 本步模型输出完毕后的完整原始文本。 */
    default void onModelComplete(String raw) {
    }

    /** 解析出的工具即将执行。 */
    default void onAction(String tool, String input) {
    }

    /** 工具执行结果（或错误提示）。 */
    default void onObservation(String observation) {
    }

    /** 格式错误、未知工具、步数用尽等状态提示。 */
    default void onNotice(String message) {
    }

    default void onDone(AgentRunResult result) {
    }

    default void onError(RuntimeException error) {
    }
}
