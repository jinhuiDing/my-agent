package com.gene.agent.agent;

import java.util.List;

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
                你是一个会用工具解决问题的助手，使用 ReAct 框架：思考 -> 行动 -> 观察，循环推进。

                可用工具:
                %s

                每一次回复都必须严格输出下面两种格式之一，而且只推进一步。

                格式 A（还要继续用工具时）:
                Thought: 你的一句话推理
                Action: 工具名（必须是上面列出的其中一个）
                Action Input: 传给工具的输入

                格式 B（信息已经足够，可以回答用户时）:
                Thought: 我已经能回答这个问题了
                Final Answer: 给用户的最终回答

                规则:
                1. Action 和 Action Input 各占一行，不要写成 JSON，不要加代码块。
                2. 一次只调用一个工具，然后停下等待 Observation。
                3. Observation 只能由系统提供，绝对不要自己编造观察结果。
                4. 涉及准确算术时用 calculator；需要事实、行情、时间、最新消息时用 search。
                5. 复杂问题先在心里拆成几个小步骤，一步一步做，不要跳步。
                6. search 的关键词要简短；搜索到的内容不一定可靠，回答时说明依据。
                7. 如果工具连续两次失败，就用已有信息输出格式 B 的 Final Answer，并说明局限。
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
