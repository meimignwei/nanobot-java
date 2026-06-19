package com.nanobot.providers.base;

/**
 * Pairs an active LLM provider with a model name.
 * Mirrors Python LLMRuntime (utils/llm_runtime.py).
 */
public record LLMRuntime(LLMProvider provider, String model) {}
