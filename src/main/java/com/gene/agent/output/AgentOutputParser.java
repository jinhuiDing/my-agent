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

    private static final JsonSchemaFactory SCHEMA_FACTORY =
            JsonSchemaFactory.getInstance(
                    SpecVersion.VersionFlag.V202012
            );

    private final ObjectMapper objectMapper;

    public AgentOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 兼容旧调用：使用默认 Schema 解析。
     */
    public AgentOutput parse(String raw) {
        return parse(raw, AgentOutputSchema.SCHEMA);
    }

    /**
     * 使用指定 Schema 解析模型输出。
     */
    public AgentOutput parse(String raw, String schemaJson) {
        if (raw == null || raw.isBlank()) {
            throw new AgentOutputParseException("模型输出不能为空");
        }

        if (schemaJson == null || schemaJson.isBlank()) {
            throw new AgentOutputParseException("Agent 输出 Schema 不能为空");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new AgentOutputParseException("模型输出不是合法 JSON", e);
        }

        // 第一层：解析并校验 JSON Schema
        JsonSchema schema;
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            schema = SCHEMA_FACTORY.getSchema(schemaNode);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new AgentOutputParseException("Agent 输出 Schema 无效", e);
        }

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
                        root.hasNonNull("thought")
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

