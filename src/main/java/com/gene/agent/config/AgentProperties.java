package com.gene.agent.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部来自 application.yml 的 agent.* 前缀。
 */
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        String baseUrl,
        String apiKey,
        String model,
        double temperature,
        int maxSteps,
        int maxObservationChars,
        Duration connectTimeout,
        Duration readTimeout,
        boolean cliEnabled) {
}
