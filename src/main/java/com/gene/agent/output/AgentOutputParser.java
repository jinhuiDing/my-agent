package com.gene.agent.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gene.agent.exception.AgentOutputParseException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;
@Component
public class AgentOutputParser {

    private final ObjectMapper objectMapper;

    private final JsonSchema schema;

    public AgentOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        try {
            JsonNode schemaNode =
                    objectMapper.readTree(AgentOutputSchema.SCHEMA);

            JsonSchemaFactory factory =
                    JsonSchemaFactory.getInstance(
                            SpecVersion.VersionFlag.V202012
                    );

            this.schema = factory.getSchema(schemaNode);

        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 输出 Schema 初始化失败", e);
        }
    }

    public AgentOutput parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AgentOutputParseException("模型输出不能为空");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new AgentOutputParseException("模型输出不是合法 JSON", e);
        }

        // 第一层：JSON Schema 校验
        Set<ValidationMessage> errors = schema.validate(root);

        if (!errors.isEmpty()) {
            String message = errors.stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .collect(Collectors.joining("; "));

            throw new AgentOutputParseException(
                    "模型输出不符合 Schema：" + message
            );
        }

        // 第二层：根据 type 转换为具体输出对象
        try {
            String type = root.path("type").asText();

            return switch (type) {
                case "action" -> new ActionOutput(
                        root.has("thought")
                                ? root.get("thought").asText()
                                : null,
                        objectMapper.treeToValue(
                                root.get("action"),
                                ActionCall.class
                        )
                );

                case "final" -> new FinalOutput(
                        root.get("answer").asText()
                );

                default -> throw new AgentOutputParseException(
                        "不支持的输出类型：" + type
                );
            };

        } catch (JsonProcessingException e) {
            throw new AgentOutputParseException("模型输出对象转换失败", e);
        }
    }


}
