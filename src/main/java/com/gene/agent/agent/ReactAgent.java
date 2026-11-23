package com.gene.agent.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gene.agent.config.AgentProperties;
import com.gene.agent.core.AgentRunTerminationReason;
import com.gene.agent.exception.AgentOutputParseException;
import com.gene.agent.llm.LlmRequest;
import com.gene.agent.llm.ResponseFormat;
import com.gene.agent.llm.message.*;
import com.gene.agent.output.*;
import com.gene.agent.runtime.AgentRunContext;
import com.gene.agent.llm.LlmClient;
import com.gene.agent.runtime.RunRegistry;
import com.gene.agent.runtime.Step;
import com.gene.agent.tool.Tool;
import com.gene.agent.tool.ToolInputValidator;
import com.gene.agent.tool.ToolRegistry;
import jakarta.annotation.PreDestroy;
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

    /**
     * 模型输出不符合 Schema 时的最大重试次数。
     */
    private static final int MAX_PARSE_RETRIES = 2;

    private final LlmClient llm;
    private final ReActPrompt prompt;
    private final AgentOutputParser parser;
    private final ToolRegistry tools;
    private final AgentProperties properties;
    private final ToolInputValidator toolInputValidator;
    private final RunRegistry runRegistry;
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setName("agent-tool-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectMapper objectMapper;

    public ReactAgent(LlmClient llm, ReActPrompt prompt, AgentOutputParser parser,
                      ToolRegistry tools, AgentProperties properties, ToolInputValidator toolInputValidator,
                      RunRegistry runRegistry, ObjectMapper objectMapper) {
        this.llm = llm;
        this.prompt = prompt;
        this.parser = parser;
        this.tools = tools;
        this.properties = properties;
        this.toolInputValidator = toolInputValidator;
        this.runRegistry = runRegistry;
        this.objectMapper = objectMapper;
    }

    public AgentRunResult run(String question) {
        return run(question, new LoggingAgentListener());
    }

    public AgentRunResult run(String question, AgentListener listener) {
        AgentRunContext context = new AgentRunContext(question, properties.maxSteps());
        context.start();
        try {
            runRegistry.register(context, properties.runTimeout());
            listener.onRunStart(context.getRunId(), question, tools.names(), context.getMaxSteps());
            // 通用 Schema：不锁死工具名（name 仅要求非空字符串）。
            // 工具清单通过 prompt 文本引导模型，未知工具名由下面的 tool == null 分支兜底，
            // 入参合法性由 ToolInputValidator 在运行时校验。
            String outputSchema = AgentOutputSchema.SCHEMA;

            String system = prompt.system();
            SystemMessage systemMessage = new SystemMessage(system);
            UserMessage userMessage = new UserMessage(context.getQuestion());

            ArrayList<Message> messages = new ArrayList<>();
            messages.add(systemMessage);
            messages.add(userMessage);

            // 每轮先检查是否已被外部终止（取消/超时），再决定是否继续推进
            while (!context.isTerminal() && context.getCurrentStep() < context.getMaxSteps()) {
                int step = context.nextStep();
                listener.onStepStart(step, context.getMaxSteps());


//                String user = prompt.user(context.getQuestion(), context.getHistory(), step);
                AgentOutput output = askModel(messages, outputSchema, listener);

                // 模型生成期间可能被外部终止，此时不再推进状态机，直接收尾
                if (context.isTerminal()) {
                    break;
                }

                if (output instanceof FinalOutput(String answer)) {
                    context.complete();
                    return finish(context, answer, true, listener);
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
                            call(tool, action.input(), listener),
                            properties.maxObservationChars()
                    );
                }
                messages.add(new ToolMessage(action.name(), observation));

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

            // 被外部终止（取消/超时）：不再调用工具，用已有信息收尾
            if (context.isTerminal()) {
                return finish(context, terminalAnswer(context), false, listener);
            }

            // 步数用尽：强行让它别再调工具，把已经拿到的信息总结成答案
            listener.onNotice("已达最大步数 " + properties.maxSteps() + "，不再调用工具，用已有信息收尾");
            String forced = summarizeWithoutTools(messages, context.getHistory(), listener);
            context.finishByLimit();
            return finish(context, forced, false, listener);
        } catch (RuntimeException ex) {
            // 中断可能让模型调用或工具调用抛异常；若外部已设置取消/超时，
            // 必须保留原终止原因，不能覆盖成 INTERNAL_ERROR。
            boolean externallyTerminated = context.isTerminal();
            if (!externallyTerminated) {
                context.fail(classifyFailure(ex));
            }
            listener.onError(ex);
            String answer = externallyTerminated ? terminalAnswer(context) : failureAnswer(ex);
            return finish(context, answer, false, listener);
        } finally {
            runRegistry.unregister(context.getRunId());
        }
    }

    /**
     * 统一的终止出口：从 context 读取终止原因构造结果，并广播 onDone。
     * 所有终止路径（成功/步数用尽/取消/超时/失败）都经过这里，保证结果结构一致。
     */
    private AgentRunResult finish(AgentRunContext context, String answer, boolean solved, AgentListener listener) {
        AgentRunResult result = buildResult(context, answer, solved);
        listener.onDone(result);
        return result;
    }

    private AgentRunResult buildResult(AgentRunContext context, String answer, boolean solved) {
        AgentRunTerminationReason reason = context.getTerminationReason();
        String stopReason = reason == null ? "unknown" : reason.name().toLowerCase(Locale.ROOT);
        return new AgentRunResult(context.getRunId(), context.getQuestion(), answer, context.getHistory(), solved, stopReason);
    }

    private AgentRunTerminationReason classifyFailure(RuntimeException ex) {
        if (ex instanceof AgentOutputParseException) {
            return AgentRunTerminationReason.PARSE_ERROR;
        }
        return AgentRunTerminationReason.INTERNAL_ERROR;
    }

    private String terminalAnswer(AgentRunContext context) {
        return switch (context.getTerminationReason()) {
            case USER_CANCEL -> "运行已被取消。";
            case TIMEOUT -> "运行已超时。";
            default -> "运行已终止：" + context.getTerminationReason();
        };
    }

    private String failureAnswer(RuntimeException ex) {
        return "运行失败：" + (ex.getMessage() == null ? ex.toString() : ex.getMessage());
    }

    /**
     * 请求模型并解析输出；当输出不符合 Schema 时把校验错误反馈给模型重试，
     * 避免单次格式偏差（如空的 final answer）直接让整次运行崩溃。
     */
    private AgentOutput askModel(List<Message> messages, String outputSchema, AgentListener listener) {

        AgentOutputParseException lastError = null;
        for (int attempt = 0; attempt < MAX_PARSE_RETRIES; attempt++) {
            LlmRequest llmRequest = new LlmRequest(messages, ResponseFormat.jsonSchema("agent_output", outputSchema));
            String raw = llm.completeStream(llmRequest, listener::onModelDelta);

            messages.add(new AssistantMessage(raw));

            listener.onModelComplete(raw);
            try {
                AgentOutput parse = parser.parse(raw, outputSchema);
                if (attempt>0) {
                    messages.subList(attempt, messages.size()).clear();
                }
                return parse;
            } catch (AgentOutputParseException ex) {
                lastError = ex;
                listener.onNotice("模型输出不符合 Schema，正在重试(" + (attempt + 1) + "/" + MAX_PARSE_RETRIES + "): " + ex.getMessage());
                messages.add(new UserMessage("上一次输出未通过校验，请严格按 Schema 重新输出。错误信息: " + ex.getMessage()));
            }
        }
        throw lastError;
    }

    private String call(Tool tool, JsonNode input, AgentListener listener) {
        String toolName = tool.name();
        String toolInput = input == null ? "{}" : input.toPrettyString();

        // 通知工具开始
        listener.onToolStart(toolName, toolInput);

        long start = System.nanoTime();

        // 1. 参数校验
        String validationError = toolInputValidator.validate(tool, input);

        if (validationError != null) {
            long costMs = (System.nanoTime() - start) / 1_000_000;

            log.warn("工具 {} 参数校验失败: {}", toolName, validationError);

            listener.onToolComplete(
                    toolName,
                    false,
                    validationError,
                    costMs
            );

            return validationError;
        }

        // 2. 在独立工作线程执行工具，避免工具超时直接影响运行线程。
        Future<String> future = toolExecutor.submit(() -> tool.execute(input));
        try {
            String result = future.get(properties.toolTimeout().toMillis(), TimeUnit.MILLISECONDS);

            if (result == null || result.isBlank()) {
                result = "工具执行成功，但没有返回内容";
            }

            long costMs = (System.nanoTime() - start) / 1_000_000;
            log.info("工具 {} 执行完成，耗时 {} ms", toolName, costMs);
            listener.onToolComplete(toolName, true, result, costMs);
            return result;
        } catch (TimeoutException ex) {
            future.cancel(true);
            long costMs = (System.nanoTime() - start) / 1_000_000;
            String error = "工具执行超时: 超过 " + properties.toolTimeout().toMillis() + " ms，已中断工具调用";
            log.warn("工具 {} 执行超时，耗时 {} ms", toolName, costMs);
            listener.onToolComplete(toolName, false, error, costMs);
            return error;
        } catch (InterruptedException ex) {
            // 用户取消或运行总超时会中断等待线程，同时取消工具工作线程。
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工具执行被中断", ex);
        } catch (ExecutionException ex) {
            long costMs = (System.nanoTime() - start) / 1_000_000;
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            String error = "工具执行失败: "
                    + (cause.getMessage() == null ? cause.toString() : cause.getMessage());
            log.error("工具 {} 执行失败，耗时 {} ms", toolName, costMs, cause);
            listener.onToolComplete(toolName, false, error, costMs);
            return error;
        }
    }

    private String summarizeWithoutTools(List<Message> messages, List<Step> history, AgentListener listener) {

        messages.add(new UserMessage("补充要求: 不允许再调用工具，直接输出 Final Answer，基于上面已有信息尽量回答，并指出还缺什么。"));
        AgentOutput output;
        try {
            LlmRequest llmRequest = new LlmRequest(messages, ResponseFormat.jsonSchema("agent_output", AgentOutputSchema.SCHEMA));

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

    @PreDestroy
    public void shutdown() {
        toolExecutor.shutdownNow();
    }

    private String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n...(观察值过长，已截断 " + (text.length() - maxChars) + " 字)";
    }
}
