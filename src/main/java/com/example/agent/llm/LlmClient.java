package com.example.agent.llm;

import java.util.function.Consumer;

/**
 * 唯一跟大模型打交道的抽象：进去一段 prompt，出来模型说的原始文本。
 * 换成别的网关只要再写一个实现即可。
 */
public interface LlmClient {

    String complete(String systemPrompt, String userPrompt);

    /**
     * 流式版本：边收边通过 onDelta 回调增量文本，返回值仍是完整文本。
     * 默认实现回退到 complete()，一次性把全文交给回调，方便假模型和测试实现。
     */
    default String completeStream(String systemPrompt, String userPrompt, Consumer<String> onDelta) {
        String full = complete(systemPrompt, userPrompt);
        onDelta.accept(full);
        return full;
    }
}
