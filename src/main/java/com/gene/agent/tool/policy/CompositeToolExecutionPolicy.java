package com.gene.agent.tool.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.gene.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompositeToolExecutionPolicy {

    private final List<ToolExecutionPolicy> policies;

    public CompositeToolExecutionPolicy(
            List<ToolExecutionPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public ToolExecutionDecision evaluate(
            Tool tool,
            JsonNode input) {

        ToolExecutionDecision approvalDecision = null;

        for (ToolExecutionPolicy policy : policies) {

            ToolExecutionDecision decision;

            try {
                decision = policy.evaluate(tool, input);
            } catch (RuntimeException e) {
                return ToolExecutionDecision.deny(
                        "策略执行异常，拒绝工具调用: " + e.getMessage()
                );
            }

            if (decision == null) {
                return ToolExecutionDecision.deny(
                        "策略返回了空决策，拒绝工具调用"
                );
            }

            switch (decision.decision()) {

                case DENY:
                    return decision;

                case REQUIRE_APPROVAL:
                    approvalDecision = decision;
                    break;

                case ALLOW:
                    break;
            }
        }

        if (approvalDecision != null) {
            return approvalDecision;
        }

        return ToolExecutionDecision.allow();
    }
}