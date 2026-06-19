package com.nanobot.providers.base;

/**
 * 活跃 LLM provider 与模型名称的配对。
 * 对应 Python LLMRuntime（utils/llm_runtime.py）。
 */
public record LLMRuntime(LLMProvider provider, String model) {}
