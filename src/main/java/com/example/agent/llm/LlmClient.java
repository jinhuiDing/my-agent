package com.example.agent.llm;

/**
 * 唯一跟大模型打交道的抽象：进去一段 prompt，出来模型说的原始文本。
 * 换成别的网关只要再写一个实现即可。
 */
public interface LlmClient {

    String complete(String systemPrompt, String userPrompt);
}
