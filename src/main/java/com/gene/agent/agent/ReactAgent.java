package com.gene.agent.agent;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.gene.agent.agent.ReActParser.ParsedStep;
import com.gene.agent.config.AgentProperties;
import com.gene.agent.llm.LlmRequest;
import com.gene.agent.llm.ResponseFormat;
import com.gene.agent.output.*;
import com.gene.agent.runtime.AgentRunContext;
import com.gene.agent.llm.LlmClient;
import com.gene.agent.runtime.AgentRunStatus;
import com.gene.agent.tool.Tool;
import com.gene.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 手写 ReAct 的心脏：一个 while 循环。
 *
 * <pre>
 *   拼 prompt（问题 + 历史） -> 问模型下一步 -> 解析出 Action
 *   -> 执行工具拿到 Observation -> 追加进历史 -> 直到 Final Answer 或步数用尽
 * </pre>
 * <p>
 * 运行过程通过 AgentListener 实时广播：CLI 逐字打印，HTTP 端点推 SSE；
 * 不传监听器时退回日志输出。
 */
@Service
public class ReactAgent {

    private static final Logger log = LoggerFactory.getLogger(ReactAgent.class);

    private final LlmClient llm;
    private final ReActPrompt prompt;
    private final AgentOutputParser parser;
    private final ToolRegistry tools;
    private final AgentProperties properties;

    public ReactAgent(LlmClient llm, ReActPrompt prompt, AgentOutputParser parser,
                      ToolRegistry tools, AgentProperties properties) {
        this.llm = llm;
        this.prompt = prompt;
        this.parser = parser;
        this.tools = tools;
        this.properties = properties;
    }

    public AgentRunResult run(String question) {
        return run(question, new LoggingAgentListener());
    }

    public AgentRunResult run(String question, AgentListener listener) {
        AgentRunContext context = new AgentRunContext(question, properties.maxSteps());
        context.start();
        try {
            listener.onRunStart(question, tools.names(), context.getMaxSteps());

            while (context.getCurrentStep() < context.getMaxSteps()) {
                int step = context.nextStep();
                listener.onStepStart(step, context.getMaxSteps());

                String system = prompt.system();
                String user = prompt.user(context.getQuestion(), context.getHistory(), step);
                LlmRequest llmRequest = new LlmRequest(system, user, ResponseFormat.jsonSchema("agent_output", AgentOutputSchema.build(tools.all())));
                String raw = llm.completeStream(llmRequest, listener::onModelDelta);

                listener.onModelComplete(raw);

                AgentOutput output = parser.parse(raw);

                if (output instanceof FinalOutput(String answer)) {
                    AgentRunResult result = new AgentRunResult(
                            question,
                            answer,
                            context.getHistory(),
                            true,
                            "final_answer"
                    );

                    context.complete();
                    listener.onDone(result);
                    return result;
                }

                ActionOutput actionOutput = (ActionOutput) output;
                ActionCall action = actionOutput.action();
                String thought = actionOutput.thought();

                String observation;
                Tool tool = tools.find(action.name()).orElse(null);

                if (tool == null) {
                    observation = "未找到工具：" + action.name();
                    listener.onNotice(observation);
                } else {
                    String actionName = tool.name();
                    String actionInput = action.input() == null
                            ? "{}"
                            : action.input().toPrettyString();

                    listener.onAction(actionName, actionInput);

                    observation = truncate(
                            call(tool, action.input()),
                            properties.maxObservationChars()
                    );
                }

                String actionInput = action.input() == null
                        ? "{}"
                        : action.input().toPrettyString();

                context.addStep(new Step(
                        thought,
                        action.name(),
                        actionInput,
                        observation
                ));

                listener.onObservation(observation);
            }
            // 步数用尽：强行让它别再调工具，把已经拿到的信息总结成答案
            listener.onNotice("已达最大步数 " + properties.maxSteps() + "，不再调用工具，用已有信息收尾");
            String forced = summarizeWithoutTools(question, context.getHistory(), listener);

            context.finishByLimit();


            AgentRunResult result = new AgentRunResult(question, forced, context.getHistory(), false, "max_steps_reached");
            listener.onDone(result);
            return result;
        } catch (RuntimeException ex) {
            if (context.getStatus() == AgentRunStatus.RUNNING) {
                context.fail();
            }
            // todo 如果 listener.onError(ex) 自己也抛异常，那么原始异常 ex 可能被覆盖。
            listener.onError(ex);
            throw ex;
        }
    }

    private String call(Tool tool, JsonNode input) {
        if (input == null || !input.isObject()) {
            return "工具参数格式错误：input 必须是 JSON 对象";
        }

        try {
            String observation = tool.execute(input);

            return observation == null || observation.isBlank()
                    ? "工具执行成功但没有返回内容"
                    : observation;

        } catch (RuntimeException ex) {
            log.warn("工具 {} 执行异常", tool.name(), ex);

            return "工具执行失败: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private String summarizeWithoutTools(String question, List<Step> history, AgentListener listener) {
        String user = prompt.user(question, history, history.size() + 1)
                + "\n\n补充要求: 不允许再调用工具，直接输出 Final Answer，基于上面已有信息尽量回答，并指出还缺什么。";
        AgentOutput output;
        try {
            LlmRequest llmRequest = new LlmRequest(prompt.system(), user, ResponseFormat.jsonSchema("agent_output", AgentOutputSchema.SCHEMA));

            output = parser.parse(llm.completeStream(llmRequest, listener::onModelDelta));
        } catch (RuntimeException ex) {
            listener.onNotice("收尾总结失败: " + ex.getMessage());
            return "步数已用完，没能得到最终答案。最后一步的观察是："
                    + (history.isEmpty() ? "(无)" : history.get(history.size() - 1).observation());
        }
        FinalOutput finalOutput = (FinalOutput) output;
        listener.onModelComplete(finalOutput.answer());
        if (finalOutput.answer() != null) {
            return finalOutput.answer();
        }
        return "步数已用完，没能得到最终答案。最后一步的观察是："
                + (history.isEmpty() ? "(无)" : history.get(history.size() - 1).observation());
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...(观察值过长，已截断 " + (text.length() - maxChars) + " 字)";
    }
}
