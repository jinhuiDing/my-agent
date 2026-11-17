package com.example.agent.tool;

/**
 * 一个工具 = 名字 + 给模型看的说明书 + 一个字符串进字符串出的动作。
 * 模型只能看到 name 和 description，所以 description 要写清楚输入格式。
 */
public interface Tool {

    String name();

    String description();

    String execute(String input);
}
