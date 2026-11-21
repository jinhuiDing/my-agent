package com.gene.agent.llm;

import com.gene.agent.output.AgentOutputSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
@ExtendWith(SpringExtension.class)
class OpenAiCompatibleLlmClientTest {
    @Autowired
    private LlmClient llmClient;
    @Test
    void aaa(){
        LlmRequest request = new LlmRequest(
                "You are an AI assistant. If you need to perform an action, return an action output. Otherwise, return a final output.",
                "查询北京今天的天气。",
                ResponseFormat.jsonSchema(
                        "agent_output",
                        AgentOutputSchema.SCHEMA
                )
        );
        String result = llmClient.complete(request);

        System.out.println("模型原始输出：");
        System.out.println(result);
    }

}
