package com.example.agent.agent;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认监听器：把过程按原来的格式打进日志，供非流式调用和排查使用。
 */
class LoggingAgentListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingAgentListener.class);

    @Override
    public void onRunStart(String question, List<String> tools, int maxSteps) {
        log.info("""
                ============== 新任务 ==============
                问题: {}
                工具: {}""", question.trim(), tools);
    }

    @Override
    public void onStepStart(int step, int maxSteps) {
        log.info("---------- 第 {}/{} 步 ----------", step, maxSteps);
    }

    @Override
    public void onModelComplete(String raw) {
        log.info("""
                模型原文:
                {}""", raw);
    }

    @Override
    public void onAction(String tool, String input) {
        log.info("[执行] {} <- {}", tool, input);
    }

    @Override
    public void onObservation(String observation) {
        log.info("[观察] {}", observation);
    }

    @Override
    public void onNotice(String message) {
        log.info("[提示] {}", message);
    }

    @Override
    public void onDone(AgentRunResult result) {
        if (result.solved()) {
            log.info("[完成] 拿到 Final Answer，共 {} 步", result.steps().size() + 1);
        } else {
            log.info("[停止] stopReason={}", result.stopReason());
        }
    }

    @Override
    public void onError(RuntimeException error) {
        log.warn("模型调用失败", error);
    }
}
