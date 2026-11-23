package com.gene.agent.agent;

import java.util.List;

import com.gene.agent.runtime.Step;
import com.gene.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

/**
 * prompt 全靠字符串拼接，没有任何模板引擎。
 * system 说明格式 + 工具清单，user 装问题和已经发生的 思考-行动-观察 历史。
 */
@Component
class ReActPrompt {

    private final ToolRegistry tools;

    ReActPrompt(ToolRegistry tools) {
        this.tools = tools;
    }

    String system() {
        return """
                你是一个能够使用工具解决问题的智能助手。你需要根据用户的问题，判断是否需要调用工具，并逐步解决问题。
                
                可用工具:
                %s
                
                工作规则:
                1. 如果需要外部信息、计算或其他工具能力，选择合适的工具执行。
                2. 每次只调用一个工具，根据工具返回的 Observation 决定下一步。
                3. Observation 只能由系统提供，不得编造工具执行结果。
                4. 涉及准确算术时优先使用 calculator。
                5. 需要事实、行情、时间、最新消息时优先使用 search。
                6. 搜索关键词应简洁明确，搜索结果可能不准确，需要结合信息判断。
                7. 如果工具调用失败，可以根据错误信息调整参数或尝试其他方案。
                8. 如果已经获得足够信息，直接给出最终回答，不要继续调用工具。
                9. 如果无法解决问题，应明确说明原因和信息局限，不得编造。
                10. 每一轮只做一个决策：要么调用一个工具，要么给出最终答案。
                11. 每次响应只能输出一个 JSON 对象，不得拼接多个对象，不得附加解释文字或 Markdown 代码块。
                
                输出格式（只能是下面两种之一）:
                
                1) 调用工具:
                {"type":"action","thought":"本轮为什么要调用该工具","action":{"name":"工具名","input":{"参数名":"参数值"}}}
                
                2) 给出最终答案:
                {"type":"final","answer":"给用户的最终答案"}
                
                格式要求:
                - 信息已经足够时，只输出上面的第 2 种格式，不要再带 action 字段，也不要输出 {"type":"action","action":{"name":"","input":{}}} 这类空占位。
                - 调用工具时，thought 只写本轮的推理和要获取的信息，不要提前写最终答案。
                - 绝对不要把 action 和 final 写在同一条回复里。
                """.formatted(tools.describeAll());
    }

    String user(String question, List<Step> history, int nextStepIndex) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题: ").append(question).append("\n\n");
        if (history.isEmpty()) {
            sb.append("请按格式输出第 1 步。\n");
            return sb.toString();
        }
        sb.append("已经发生的步骤:\n");
        for (int i = 0; i < history.size(); i++) {
            sb.append(history.get(i).toPromptBlock(i + 1)).append('\n');
        }
        sb.append("\n请注意：上面每条 Observation 是系统真实执行工具得到的，不是你写的。")
                .append("现在请按格式输出第 ").append(nextStepIndex).append(" 步。");
        return sb.toString();
    }
}
