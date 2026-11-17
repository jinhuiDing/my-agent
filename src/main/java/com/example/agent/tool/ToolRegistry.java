package com.example.agent.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Spring 把所有 Tool Bean 注进来，主循环只按名字查表调用。
 * 加新工具只要再写一个 @Component，不用改 agent 代码。
 */
@Component
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> discovered) {
        for (Tool tool : discovered) {
            tools.put(tool.name(), tool);
        }
    }

    public Optional<Tool> find(String rawName) {
        if (rawName == null) {
            return Optional.empty();
        }
        String normalized = rawName.trim();
        Tool exact = tools.get(normalized);
        if (exact != null) {
            return Optional.of(exact);
        }
        // 模型经常写成 "Tool: search" 或 "Search"，做一次宽松匹配兜底
        return tools.values().stream()
                .filter(tool -> tool.name().equalsIgnoreCase(normalized)
                        || normalized.toLowerCase().contains(tool.name().toLowerCase()))
                .findFirst();
    }

    /** 渲染成 system prompt 里的工具清单。 */
    public String describeAll() {
        return tools.values().stream()
                .map(tool -> tool.name() + ": " + tool.description())
                .collect(Collectors.joining("\n"));
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }
}
