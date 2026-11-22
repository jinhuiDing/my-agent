package com.gene.agent.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gene.agent.exception.AgentOutputParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOutputParserTest {

    private final AgentOutputParser parser = new AgentOutputParser(new ObjectMapper());

    @Test
    void parsesSingleValidAction() {
        String raw = """
                {"type":"action","thought":"需要计算","action":{"name":"calculator","input":{"expression":"(237 + 45) * 1.13"}}}
                """;

        ActionOutput output = assertInstanceOf(ActionOutput.class, parser.parse(raw));

        assertEquals("需要计算", output.thought());
        assertEquals("calculator", output.action().name());
        assertEquals("(237 + 45) * 1.13", output.action().input().path("expression").asText());
    }

    @Test
    void recoversValidActionWhenEmptyFinalPlaceholderIsConcatenatedBeforeIt() {
        // 截图形态：非法的空 final 占位 + 合法 action
        String raw = """
                {"type":"final","answer":""}{"type":"action","thought":"需要计算","action":{"name":"calculator","input":{"expression":"(237 + 45) * 1.13"}}}
                """;

        ActionOutput output = assertInstanceOf(ActionOutput.class, parser.parse(raw));

        assertEquals("calculator", output.action().name());
    }

    @Test
    void recoversValidFinalWhenEmptyActionPlaceholderIsConcatenatedBeforeIt() {
        // 本轮截图形态：空 action 占位 + 合法 final
        String raw = """
                {"action":{"input":{},"name":""},"thought":"信息已足够","type":"action"}{"answer":"318.66","type":"final"}
                """;

        FinalOutput output = assertInstanceOf(FinalOutput.class, parser.parse(raw));

        assertEquals("318.66", output.answer());
    }

    @Test
    void rejectsTwoValidDecisions() {
        String raw = """
                {"type":"final","answer":"318.66"}{"type":"action","thought":"再算一次","action":{"name":"calculator","input":{"expression":"1+1"}}}
                """;

        AgentOutputParseException ex = assertThrows(AgentOutputParseException.class, () -> parser.parse(raw));

        assertTrue(ex.getMessage().contains("多个合法决策"));
    }

    @Test
    void rejectsTwoValidActions() {
        String raw = """
                {"type":"action","thought":"第一步","action":{"name":"calculator","input":{"expression":"1+1"}}}{"type":"action","thought":"第二步","action":{"name":"calculator","input":{"expression":"2+2"}}}
                """;

        AgentOutputParseException ex = assertThrows(AgentOutputParseException.class, () -> parser.parse(raw));

        assertTrue(ex.getMessage().contains("多个合法决策"));
    }

    @Test
    void reportsErrorsForAllInvalidObjects() {
        String raw = """
                {"type":"final","answer":""}{"type":"action","thought":"缺少工具名","action":{"input":{}}}
                """;

        AgentOutputParseException ex = assertThrows(AgentOutputParseException.class, () -> parser.parse(raw));

        assertTrue(ex.getMessage().contains("对象 1"));
        assertTrue(ex.getMessage().contains("对象 2"));
    }

    @Test
    void rejectsTrailingGarbageAfterValidObject() {
        String raw = """
                {"type":"final","answer":"318.66"} 以上是最终答案
                """;

        AgentOutputParseException ex = assertThrows(AgentOutputParseException.class, () -> parser.parse(raw));

        assertTrue(ex.getMessage().contains("不是合法 JSON"));
    }
}
