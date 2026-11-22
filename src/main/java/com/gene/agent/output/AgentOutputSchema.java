package com.gene.agent.output;

/**
 * Agent 输出的统一 JSON Schema。
 *
 * <p>设计：只用一份通用 Schema 约束"输出形态"（action / final 两种），
 * 不用 const 锁死工具名——工具清单通过 prompt 文本引导模型，
 * 未知工具名由 ReactAgent 的 tool == null 分支兜底，
 * 工具入参合法性由 ToolInputValidator 在运行时校验。
 *
 * <p>这样模型幻觉出未注册工具名时，能进入兜底分支、得到"未找到工具"的观察反馈并自我纠正，
 * 而不是在 Schema 校验阶段被 oneOf + const 反复拒绝（那会让兜底分支成为死代码）。
 */
public class AgentOutputSchema {
    private AgentOutputSchema() {
    }

    public static final String SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "oneOf": [
                {
                  "type": "object",
                  "properties": {
                    "type": {
                      "type": "string",
                      "const": "action"
                    },
                    "thought": {
                      "type": ["string", "null"]
                    },
                    "action": {
                      "type": "object",
                      "properties": {
                        "name": {
                          "type": "string",
                          "minLength": 1
                        },
                        "input": {
                          "type": "object",
                          "additionalProperties": true
                        }
                      },
                      "required": ["name", "input"],
                      "additionalProperties": false
                    }
                  },
                  "required": ["type", "thought", "action"],
                  "additionalProperties": false
                },
                {
                  "type": "object",
                  "properties": {
                    "type": {
                      "type": "string",
                      "const": "final"
                    },
                    "answer": {
                      "type": "string",
                      "minLength": 1
                    }
                  },
                  "required": ["type", "answer"],
                  "additionalProperties": false
                }
              ]
            }
            """;
}
