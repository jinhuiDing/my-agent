package com.gene.agent.output;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gene.agent.exception.AgentOutputParseException;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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

        // 第一层：读取全部根 JSON 对象，避免 Jackson 默认忽略 trailing token
        List<JsonNode> roots = readRootValues(raw);

        JsonSchema schema;
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            schema = SCHEMA_FACTORY.getSchema(schemaNode);
        } catch (JsonProcessingException | RuntimeException e) {
            throw new AgentOutputParseException("Agent 输出 Schema 无效", e);
        }

        // 第二层：逐个校验，记录合法候选与错误信息
        List<JsonNode> valid = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < roots.size(); i++) {
            Set<ValidationMessage> messages = schema.validate(roots.get(i));
            if (messages.isEmpty()) {
                valid.add(roots.get(i));
            } else {
                errors.add("对象 " + (i + 1) + ": " + messages.stream()
                        .map(ValidationMessage::getMessage)
                        .sorted()
                        .collect(Collectors.joining("; ")));
            }
        }

        // 唯一合法候选：单对象合法直接转换；多对象时只有恰好一个合法才恢复它
        if (valid.size() == 1) {
            return toOutput(valid.get(0));
        }

        if (valid.isEmpty()) {
            throw new AgentOutputParseException(
                    "模型输出不符合 Schema：" + String.join(" | ", errors)
            );
        }

        // 多个合法决策：不猜测意图，交给模型重试
        throw new AgentOutputParseException(
                "模型输出包含多个合法决策，每次只能输出一个 action 或 final，实际有 " + valid.size() + " 个"
        );
    }

    /**
     * 顺序读取字符串中的全部根 JSON 值。
     * 任何位置出现非法 JSON 或尾随垃圾文本都直接报错，不静默忽略。
     */
    private List<JsonNode> readRootValues(String raw) {
        List<JsonNode> roots = new ArrayList<>();
        try (JsonParser parser = objectMapper.getFactory().createParser(raw)) {
            while (parser.nextToken() != null) {
                JsonNode node = objectMapper.readTree(parser);
                if (node != null) {
                    roots.add(node);
                }
            }
        } catch (IOException e) {
            throw new AgentOutputParseException("模型输出不是合法 JSON", e);
        }
        return roots;
    }

    /**
     * 根据 type 把合法 JSON 转换为具体输出对象。
     */
    private AgentOutput toOutput(JsonNode root) {
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

