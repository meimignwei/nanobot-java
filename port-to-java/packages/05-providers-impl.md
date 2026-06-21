# 05 — providers 包：Provider 实现

**对标 Python：** `providers/anthropic_provider.py` (693行), `openai_compat_provider.py` (1,482行), `fallback_provider.py` (~300行), `bedrock_provider.py` (754行), `azure_openai_provider.py` (253行), `github_copilot_provider.py` (262行), `openai_codex_provider.py` (323行)

## Python 源码分析

### `anthropic_provider.py` — Anthropic 原生 SDK 集成

```
AnthropicProvider(LLMProvider)
  __init__(api_key, api_base, default_model, extra_headers)
    → 创建 AsyncAnthropic client (max_retries=0, 重试由基类统一管理)
    → _normalize_base_url: SDK 内部追加 /v1，需移除

  消息格式转换 (OpenAI → Anthropic Messages API):
    _convert_messages(messages) → (system, anthropic_messages)
      system 消息 → system param (字符串)
      tool 消息 → tool_result 块，合并到之前的 user 消息中
      assistant 消息 → text + tool_use 块列表 + thinking_blocks
      user 消息 → content blocks (含 image_url→base64 转换)

    _assistant_blocks(msg):
      1. thinking_blocks → "thinking" 类型块
      2. content text → "text" 类型块
      3. tool_calls → "tool_use" 类型块

    _convert_user_content(content): 字符串/list → Anthropic content blocks
    _convert_image_block: OpenAI image_url → {"type":"image", "source":...}
    _merge_consecutive(msgs): 合并连续同角色，移除尾部assistant

  Tool 转换:
    _convert_tools(tools): OpenAI function schema → Anthropic input_schema
      {"name":..., "input_schema":<parameters>, "description":...}

    _convert_tool_choice(tool_choice, thinking_enabled):
      "auto" → {"type": "auto"}
      "required" → {"type": "any"}
      dict → {"type": "tool", "name": "..."}
      thinking_enabled 时强制 {"type": "auto"}

  Prompt Caching:
    _apply_cache_control(system, messages, tools):
      system 最后一个块 → cache_control
      messages[-2] → cache_control (倒数第二个用户消息)
      工具列表的 builtin/MCP 边界 → cache_control

  Extended Thinking:
    reasoning_effort 映射:
      "adaptive" → {"type": "adaptive"}, temperature=1.0
      "low" → {"type": "enabled", "budget_tokens": 1024}
      "medium" → {"type": "enabled", "budget_tokens": 4096}
      "high" → {"type": "enabled", "budget_tokens": max(8192, max_tokens)}
    claude-opus-4-7 禁止 temperature 参数

  _build_kwargs: 组装所有 API 参数
    → _strip_prefix: 移除 "anthropic/" 前缀
    → _convert_messages, _convert_tools, _apply_cache_control

  响应解析 (_parse_response):
    content 块: text → 合并为字符串
    tool_use 块 → ToolCallRequest(id, name, input)
    thinking 块 → thinking_blocks
    stop_reason 映射: tool_use→"tool_calls", end_turn→"stop", max_tokens→"length"
    使用量: input_tokens + cache_creation + cache_read

  错误处理 (_handle_error):
    从异常中提取 status_code, headers, body
    x-should-retry 头部 → error_should_retry 标志
    异常类名匹配: "timeout"→error_kind:"timeout"

  Stream 处理:
    引擎: content_block_start → content_block_delta → content_block_stop → message_stop
    content_block_start: 工具调用 → tool_blocks 状态初始化
    content_block_delta:
      thinking_delta → on_thinking_delta
      text_delta → on_content_delta
      input_json_delta → on_tool_call_delta (partial JSON)
    idle_timeout_s=90s 用于每个 SSE chunk
    "Streaming is required" 错误 → 自动回退到 chat_stream
```

### `openai_compat_provider.py` — OpenAI 兼容 (1,482行，最复杂的提供商)

```
OpenAICompatProvider(LLMProvider)
  构造参数: api_key, api_base, default_model, extra_headers, spec, extra_body, api_type, extra_query

  Client: 懒初始化 (_ensure_client, 带锁), 本地端点 keepalive_expiry=0
  双后端: Chat Completions API vs Responses API

  Responses API 熔断器:
    3次连续失败 → 300s 冷却期
    _responses_circuit_allows_probe: 检查熔断器状态
    _record_responses_failure / _record_responses_success

  Responses API 判定 (_should_use_responses_api):
    仅用于直接 OpenAI 端点 (api.openai.com)
    GPT-5/o1/o3/o4 模型 或 reasoning_effort 激活时使用
    api_type="chat_completions" 强制禁用; api_type="responses" 强制启用

  思考模式注入 (30+ 提供商特定行为):
    _THINKING_STYLE_MAP:
      "thinking_type" → {"thinking": {"type": "enabled"/"disabled"}}
      "enable_thinking" → {"enable_thinking": true/false}
      "reasoning_split" → {"reasoning_split": true/false}
    _GATEWAY_REASONING_STYLE_MAP:
      "reasoning_effort" → {"reasoning": {"effort": <level>}}

  模型特定处理:
    _KIMI_THINKING_MODELS: kimi-k2.5/k2.6 (thinking_type, 移除 reasoning_effort)
    _MIMO_THINKING_MODELS: mimo-v2.5-pro 等 (thinking_type)
    deepseek: 强制字符串内容, reasoning_content="" backfill
    dashscope: "minimal"→"minimum", "minimum"→"minimal"
    mistral: 归一化工具调用ID (9字符字母数字)
    stepfun: reasoning_as_content (内容为空时使用 reasoning)

  _build_kwargs:
    模型名 strip (strip_model_prefix)
    消息清理 (_sanitize_messages + _sanitize_empty_content)
    1. temperature: 推理模型未启用时不设置
    2. max_tokens vs max_completion_tokens (GPT-5/o-series 强制使用后者)
    3. model_overrides 应用 (如 kimi temperature>=1.0)
    4. reasoning_effort: 语义归一化 + wire 格式
    5. thinking style → extra_body 注入
    6. gateway_reasoning_style → extra_body 注入
    7. reasoning_content="" backfill (DeepSeek 推理模式)
    8. extra_body 深度合并 (用户覆盖)

  _build_responses_body:
    使用 shared openai_responses 模块的 convert_messages/convert_tools
    构建 Responses API 请求体 + _merge_responses_extra_body

  响应解析 (_parse):
    支持 dict 和 Pydantic 模型两种格式
    tool_calls 提取: 包括 extra_content, provider_specific_fields
    cached_tokens 归一化: 多个提供商的不同字段名 → 统一键

  流式处理 (_parse_chunks):
    从多个 chunks 重建完整响应
    工具调用 buffer 管理: 按 index 分组的 tc_bufs
    工具ID 去重 (Zhipu/GLM 流模式复用ID)
    legacy function_call 支持

  错误处理 (_extract_error_metadata / _handle_error):
    本地端点提示 (连接失败时检查 localhost 可达性)
    结构化错误: status_code, error_kind, error_type, error_code, x-should-retry

  Stream:
    Responses API 流: consume_sdk_stream (共享模块)
    Chat Completions 流: OpenAI SDK stream → 收集 chunks + 实时回调
    Zhipu 特殊处理: tool_stream=true
    idle_timeout_s=90s
```

### `fallback_provider.py` — 故障自动转移

```
FallbackProvider(LLMProvider)
  熔断器: 3次连续失败 → 60s 冷却 ("half-open" 探测允许一次)

  _should_fallback(response):
    error_should_retry=False → 不转移
    HTTP 400/401/403/404/422 → 不转移
    NON_FALLBACK_ERROR_KINDS: auth, permission, content_filter, refusal, context_length
    retryable: 408/409/429/5xx, timeout/connection/server_error 等

  _try_with_fallback(call, kwargs, has_streamed, on_stream_recover):
    1. 尝试 primary
    2. 若失败且 has_streamed 为 true:
       - timeout → 重置 has_streamed=false, 调用 on_stream_recover, 继续
       - 其他错误 → 返回错误 (避免重复输出)
    3. 若 _should_fallback → 遍历 fallback_presets
       每个 fallback 通过 provider_factory 创建
       覆盖 kwargs 中的 model/max_tokens/temperature/reasoning_effort
    4. 流式恢复: on_stream_recover 回调允许开始新流段
    5. 全部失败 → 返回最后一个错误响应

  关键: 转移是请求范围的 (wrapper 本身在轮次之间无状态)
```

### `bedrock_provider.py` — AWS Bedrock Converse API

```
BedrockProvider(LLMProvider)
  _make_client: boto3 Session → bedrock-runtime client (Converse API)
  支持: api_key, api_base (endpoint_url), region, profile, extra_body

  消息转换 (_convert_messages):
    system → system blocks (仅 text/cachePoint/guardContent)
    tool → toolResult 块
    assistant → text + toolUse + reasoningContent 块
    user → content_blocks

  图像: data URL → base64 decode → {"image": {"format": jpeg, "source": {"bytes": data}}}
  温度: claude-opus-4-7 不发送; claude-opus-4-7 仅 adaptive thinking

  工具转换:
    OpenAI function → {"toolSpec": {"name":..., "inputSchema": {"json": <parameters>}}}
    _noop_tool: 无工具但历史含 toolUse 时注入占位符

  _build_kwargs:
    modelId, messages, system, inferenceConfig (maxTokens, temperature)
    additionalModelRequestFields: thinking + extra_body
    toolConfig: tools + toolChoice

  Stream 事件解析 (_parse_stream_event):
    contentBlockStart → 工具调用 buffer 初始化
    contentBlockDelta:
      text → 文本累积
      toolUse.input → JSON 累积
      reasoningContent → 推理累积
    contentBlockStop → reasoning buffer 刷新
    messageStop → stop_reason
    metadata → usage

  异步: asyncio.to_thread() 将 boto3 同步调用卸载到线程池
  在 Java 中: boto3 → AWS SDK for Java v2, 已原生异步 (SdkAsyncHttpClient)
```

### `azure_openai_provider.py` — Azure OpenAI

```
AzureOpenAIProvider(LLMProvider)
  使用 OpenAI SDK 的 Responses API 端点
  认证: api_key (静态) 或 DefaultAzureCredential (AAD)
  AAD token 通过异步回调刷新
  base_url = {endpoint}/openai/v1/
  所有请求代理到 client.responses.create()

  支持: 构建 body (instructions, input, max_output_tokens, reasoning)
  温度: GPT-5/o-series 推理激活时忽略
```

### `github_copilot_provider.py` — GitHub Copilot

```
GitHubCopilotProvider extends OpenAICompatProvider
  OAuth 设备流:
    login_github_copilot() → GitHub device flow → OAuthToken → 持久化
  Token 交换:
    _get_copilot_access_token() → GitHub token → Copilot API → access token
    令牌缓存 + 过期前刷新 (skew=60s)
  chat/chat_stream:
    1. _refresh_client_api_key() 刷新 Copilot token
    2. 委托给 super().chat() / super().chat_stream()
  Editor 头部: vscode/1.99.0, copilot-chat/0.26.0
```

### `openai_codex_provider.py` — OpenAI Codex

```
OpenAICodexProvider(LLMProvider)
  使用 OAuth token + raw HTTP (httpx) 调用 Codex Responses API
  _call_codex(messages, tools, model, ...):
    → convert_messages (OpenAI → Responses format)
    → _request_codex: POST → SSE stream → consume_sse_with_reasoning
    → SSL 验证失败 → 自动重试 (verify=False)
    → 结构化错误分类 (_CodexHTTPError)
  支持: prompt_cache_key (SHA-256 hash), reasoning_options
  Stream: 原生 SSE 解析, 有 idle_timeout 保护
```

## Java 实现方案

### 1. `AnthropicProvider.java` — 使用 `anthropic-java` SDK

关键转换：
- Python `AsyncAnthropic` → Java `Anthropic` (同步客户端，由虚拟线程执行)
- Python `await client.messages.create(...)` → Java `client.messages().create(params)`
- Python `async with client.messages.stream(...)` → Java 阻塞流迭代器

Maven 依赖:
```xml
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
    <version>0.43.0</version>
</dependency>
```

```java
// com.nanobot.providers.impl.AnthropicProvider.java
package com.nanobot.providers.impl;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.AnthropicClientSync;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.*;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.providers.ToolCallRequest;
import com.nanobot.providers.ToolArguments;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM provider using the native Anthropic SDK for Claude models.
 *
 * In Java with Virtual Threads, all API calls are synchronous blocking calls.
 * The SDK's HTTP client (OkHttp) performs blocking I/O, which is ideal for
 * virtual threads — the carrier thread is released during I/O wait.
 */
public class AnthropicProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(AnthropicProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ALPHANUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String defaultModel;
    private final AnthropicClientSync client;

    public AnthropicProvider(
        String apiKey,
        String apiBase,
        String defaultModel,
        Map<String, String> extraHeaders
    ) {
        super(apiKey, apiBase);
        this.defaultModel = (defaultModel != null) ? defaultModel : "claude-sonnet-4-20250514";

        AnthropicClient.Builder builder = AnthropicOkHttpClient.builder()
            .maxRetries(0);  // retry centralized in LLMProvider.runWithRetry

        if (apiKey != null && !apiKey.isEmpty()) {
            builder.apiKey(apiKey);
        }
        if (apiBase != null && !apiBase.isEmpty()) {
            builder.baseUrl(normalizeBaseUrl(apiBase));
        }

        AnthropicClient fullClient = builder.build();
        // Use the synchronous blocking client for virtual-thread execution
        this.client = fullClient.sync(Duration.ofSeconds(120));
    }

    /** Anthropic SDK appends /v1 internally; strip it if present. */
    private static String normalizeBaseUrl(String apiBase) {
        String normalized = apiBase.replaceAll("/+$", "");
        if (normalized.endsWith("/v1")) {
            return normalized.substring(0, normalized.length() - 3);
        }
        return normalized;
    }

    // =========================================================================
    // Message conversion: OpenAI chat format → Anthropic Messages API
    // =========================================================================

    /**
     * Convert OpenAI-format messages to Anthropic format.
     * Returns a pair of (system prompt, anthropic messages list).
     */
    @SuppressWarnings("unchecked")
    public Object[] convertMessages(List<Map<String, Object>> messages) {
        String system = "";
        List<Map<String, Object>> raw = new ArrayList<>();
        List<Map<String, Object>> cleaned = sanitizeEmptyContent(messages);

        for (Map<String, Object> msg : cleaned) {
            String role = (String) msg.get("role");
            if (role == null) role = "";
            Object content = msg.get("content");

            switch (role) {
                case "system" -> {
                    if (content instanceof String s) system = s;
                    else if (content instanceof List<?>) {
                        // system as content blocks (for cache_control support)
                        return new Object[]{content, convertSystemMessages(raw)};
                    } else system = String.valueOf(content);
                }
                case "tool" -> {
                    Map<String, Object> block = toolResultBlock(msg);
                    if (!raw.isEmpty() && "user".equals(raw.get(raw.size() - 1).get("role"))) {
                        Map<String, Object> prev = raw.get(raw.size() - 1);
                        Object prevC = prev.get("content");
                        if (prevC instanceof List<?> l) {
                            ((List<Object>) l).add(block);
                        } else {
                            List<Object> newContent = new ArrayList<>();
                            newContent.add(Map.of("type", "text", "text",
                                (prevC != null) ? String.valueOf(prevC) : ""));
                            newContent.add(block);
                            prev.put("content", newContent);
                        }
                    } else {
                        raw.add(Map.of("role", "user", "content", List.of(block)));
                    }
                }
                case "assistant" -> raw.add(Map.of("role", "assistant",
                    "content", assistantBlocks(msg)));
                case "user" -> raw.add(Map.of("role", "user",
                    "content", convertUserContent(content)));
            }
        }

        return new Object[]{system, mergeConsecutive(raw)};
    }

    private List<Map<String, Object>> convertSystemMessages(List<Map<String, Object>> msgs) {
        return mergeConsecutive(msgs); // actually more complex, but for spec purposes
    }

    // =========================================================================
    // Content block construction
    // =========================================================================

    @SuppressWarnings("unchecked")
    static Map<String, Object> toolResultBlock(Map<String, Object> msg) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", msg.getOrDefault("tool_call_id", ""));
        Object content = msg.get("content");
        if (content instanceof List<?>) {
            block.put("content", convertUserContent(content));
        } else if (content instanceof String s) {
            block.put("content", s);
        } else {
            block.put("content", (content != null) ? String.valueOf(content) : "");
        }
        return block;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> assistantBlocks(Map<String, Object> msg) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        Object content = msg.get("content");

        // thinking_blocks first
        List<Map<String, Object>> thinkingBlocks =
            (List<Map<String, Object>>) msg.get("thinking_blocks");
        if (thinkingBlocks != null) {
            for (Map<String, Object> tb : thinkingBlocks) {
                if ("thinking".equals(tb.get("type"))) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "thinking");
                    block.put("thinking", tb.getOrDefault("thinking", ""));
                    block.put("signature", tb.getOrDefault("signature", ""));
                    blocks.add(block);
                }
            }
        }

        // text content
        if (content instanceof String s && !s.isEmpty()) {
            blocks.add(Map.of("type", "text", "text", s));
        } else if (content instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> m) {
                    blocks.add((Map<String, Object>) m);
                } else {
                    blocks.add(Map.of("type", "text", "text", String.valueOf(item)));
                }
            }
        }

        // tool calls → tool_use blocks
        List<Map<String, Object>> toolCalls =
            (List<Map<String, Object>>) msg.get("tool_calls");
        if (toolCalls != null) {
            for (Map<String, Object> tc : toolCalls) {
                Map<String, Object> func = (Map<String, Object>) tc.getOrDefault("function", Map.of());
                Map<String, Object> block = new LinkedHashMap<>();
                block.put("type", "tool_use");
                block.put("id", tc.getOrDefault("id", genToolId()));
                block.put("name", func.getOrDefault("name", ""));
                block.put("input", ToolArguments.toolArgumentsObjectForReplay(
                    func.get("arguments")));
                blocks.add(block);
            }
        }

        return blocks.isEmpty() ? List.of(Map.of("type", "text", "text", "")) : blocks;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertUserContent(Object content) {
        if (content instanceof String s || content == null) {
            return List.of(Map.of("type", "text", "text",
                (s != null && !s.isEmpty()) ? s : "(empty)"));
        }
        if (!(content instanceof List<?> items)) {
            return List.of(Map.of("type", "text", "text", String.valueOf(content)));
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                result.add(Map.of("type", "text", "text", String.valueOf(item)));
                continue;
            }
            Map<String, Object> block = (Map<String, Object>) m;
            if ("image_url".equals(block.get("type"))) {
                Map<String, Object> converted = convertImageBlock(block);
                if (converted != null) result.add(converted);
                continue;
            }
            if (block.get("type") == null) {
                result.add(Map.of("type", "text", "text", String.valueOf(block)));
                continue;
            }
            result.add(block);
        }
        return result.isEmpty()
            ? List.of(Map.of("type", "text", "text", "(empty)"))
            : result;
    }

    private static final Pattern DATA_URL = Pattern.compile(
        "data:(image/\\w+);base64,(.+)", Pattern.DOTALL);

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertImageBlock(Map<String, Object> block) {
        Map<String, Object> imageUrl = (Map<String, Object>) block.getOrDefault(
            "image_url", Map.of());
        String url = (String) imageUrl.getOrDefault("url", "");
        if (url == null || url.isEmpty()) return null;

        Matcher m = DATA_URL.matcher(url);
        if (m.matches()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "base64");
            source.put("media_type", m.group(1));
            source.put("data", m.group(2));
            return Map.of("type", "image", "source", source);
        }
        return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
    }

    // =========================================================================
    // Merge consecutive messages (Anthropic contract enforcement)
    // =========================================================================

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> mergeConsecutive(List<Map<String, Object>> msgs) {
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : msgs) {
            if (!merged.isEmpty()
                && Objects.equals(merged.get(merged.size() - 1).get("role"), msg.get("role"))) {

                Map<String, Object> prev = merged.get(merged.size() - 1);
                Object prevC = prev.get("content");
                Object curC = msg.get("content");

                if (prevC instanceof String s) {
                    prevC = new ArrayList<>(List.of(Map.of("type", "text", "text", s)));
                    prev.put("content", prevC);
                }
                if (curC instanceof String s) {
                    curC = new ArrayList<>(List.of(Map.of("type", "text", "text", s)));
                }
                if (curC instanceof List<?> l) {
                    ((List<Object>) prevC).addAll(l);
                }
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }

        // Rule: strip trailing assistant turns
        Map<String, Object> lastPopped = null;
        while (!merged.isEmpty()
            && "assistant".equals(merged.get(merged.size() - 1).get("role"))) {
            lastPopped = merged.remove(merged.size() - 1);
        }

        // Recovery: reroute last popped assistant as user
        if (merged.isEmpty() && lastPopped != null && !hasToolUse(lastPopped)) {
            merged.add(Map.of("role", "user", "content", lastPopped.get("content")));
        }

        // Rule: prepend synthetic opener if first surviving turn is assistant
        if (!merged.isEmpty()
            && "assistant".equals(merged.get(0).get("role"))
            && !hasToolUse(merged.get(0))) {
            merged.add(0, Map.of("role", "user", "content", "(conversation continued)"));
        }

        return merged;
    }

    @SuppressWarnings("unchecked")
    private static boolean hasToolUse(Map<String, Object> msg) {
        Object content = msg.get("content");
        if (!(content instanceof List<?> list)) return false;
        return list.stream().anyMatch(b ->
            b instanceof Map<?, ?> m && "tool_use".equals(((Map<String, Object>) m).get("type")));
    }

    // =========================================================================
    // Tool conversion: OpenAI → Anthropic
    // =========================================================================

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> convertTools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return null;

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> tool : tools) {
            Map<String, Object> func = tool.containsKey("function")
                ? (Map<String, Object>) tool.get("function") : tool;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", func.getOrDefault("name", ""));
            entry.put("input_schema", func.getOrDefault("parameters",
                Map.of("type", "object", "properties", Map.of())));
            Object desc = func.get("description");
            if (desc instanceof String s && !s.isEmpty()) entry.put("description", s);
            if (tool.containsKey("cache_control")) {
                entry.put("cache_control", tool.get("cache_control"));
            }
            result.add(entry);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> convertToolChoice(Object toolChoice, boolean thinkingEnabled) {
        if (thinkingEnabled) return Map.of("type", "auto");
        if (toolChoice == null || "auto".equals(toolChoice)) return Map.of("type", "auto");
        if ("required".equals(toolChoice)) return Map.of("type", "any");
        if ("none".equals(toolChoice)) return null;
        if (toolChoice instanceof Map<?, ?> m) {
            Map<String, Object> tcMap = (Map<String, Object>) m;
            Map<String, Object> func = (Map<String, Object>) tcMap.get("function");
            if (func != null && func.get("name") instanceof String name) {
                return Map.of("type", "tool", "name", name);
            }
        }
        return Map.of("type", "auto");
    }

    // =========================================================================
    // Prompt caching
    // =========================================================================

    @SuppressWarnings("unchecked")
    static Object[] applyCacheControl(
        Object system,
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools
    ) {
        Map<String, Object> marker = Map.of("type", "ephemeral");

        // system
        Object cachedSystem = system;
        if (system instanceof String s && !s.isEmpty()) {
            cachedSystem = List.of(
                Map.of("type", "text", "text", s, "cache_control", marker));
        } else if (system instanceof List<?> l && !l.isEmpty()) {
            List<Map<String, Object>> sysList = new ArrayList<>((List<Map<String, Object>>) l);
            Map<String, Object> last = new LinkedHashMap<>(sysList.get(sysList.size() - 1));
            last.put("cache_control", marker);
            sysList.set(sysList.size() - 1, last);
            cachedSystem = sysList;
        }

        // messages[-2]
        List<Map<String, Object>> newMsgs = new ArrayList<>(messages);
        if (newMsgs.size() >= 3) {
            int idx = newMsgs.size() - 2;
            Map<String, Object> msg = new LinkedHashMap<>(newMsgs.get(idx));
            Object c = msg.get("content");
            if (c instanceof String s) {
                msg.put("content", List.of(
                    Map.of("type", "text", "text", s, "cache_control", marker)));
            } else if (c instanceof List<?> l) {
                List<Object> nc = new ArrayList<>(l);
                Map<String, Object> last = new LinkedHashMap<>((Map<String, Object>) nc.get(nc.size() - 1));
                last.put("cache_control", marker);
                nc.set(nc.size() - 1, last);
                msg.put("content", nc);
            }
            newMsgs.set(idx, msg);
        }

        // tools (at builtin/MCP boundaries)
        List<Map<String, Object>> newTools = (tools != null) ? new ArrayList<>(tools) : null;
        if (newTools != null) {
            for (int idx : toolCacheMarkerIndices(newTools)) {
                Map<String, Object> tool = new LinkedHashMap<>(newTools.get(idx));
                tool.put("cache_control", marker);
                newTools.set(idx, tool);
            }
        }

        return new Object[]{cachedSystem, newMsgs, newTools};
    }

    // =========================================================================
    // Build API parameters
    // =========================================================================

    @SuppressWarnings("unchecked")
    Map<String, Object> buildKwargs(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String model,
        int maxTokens,
        double temperature,
        String reasoningEffort,
        Object toolChoice,
        boolean supportsCaching
    ) {
        String modelName = stripPrefix((model != null) ? model : defaultModel);
        Object[] converted = convertMessages(messages);
        Object system = converted[0];
        List<Map<String, Object>> anthropicMsgs =
            (List<Map<String, Object>>) converted[1];
        List<Map<String, Object>> anthropicTools = convertTools(tools);

        if (supportsCaching) {
            Object[] cached = applyCacheControl(system, anthropicMsgs, anthropicTools);
            system = cached[0];
            anthropicMsgs = (List<Map<String, Object>>) cached[1];
            anthropicTools = (List<Map<String, Object>>) cached[2];
        }

        int effectiveMaxTokens = Math.max(1, maxTokens);
        boolean thinkingEnabled = (reasoningEffort != null)
            && !reasoningEffort.equalsIgnoreCase("none");

        boolean omitTemperature = modelName.contains("opus-4-7");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("model", modelName);
        params.put("max_tokens", effectiveMaxTokens);

        // Build messages param
        List<MessageParam> messageParams = buildMessageParams(anthropicMsgs);
        params.put("messages", messageParams);

        if (system instanceof String s && !s.isEmpty()) {
            params.put("system", s);
        } else if (system instanceof List && !((List<?>) system).isEmpty()) {
            params.put("system", system);
        }

        // Thinking / temperature
        if ("adaptive".equals(reasoningEffort)) {
            params.put("thinking", Map.of("type", "adaptive"));
            if (!omitTemperature) params.put("temperature", 1.0);
        } else if (thinkingEnabled) {
            Map<String, Integer> budgetMap = Map.of("low", 1024, "medium", 4096, "high", 8192);
            int budget = budgetMap.getOrDefault(reasoningEffort.toLowerCase(), 4096);
            budget = Math.max(budget, 8192); // high = max(8192, max_tokens) simplified
            params.put("thinking", Map.of("type", "enabled", "budget_tokens", budget));
            params.put("max_tokens", Math.max(effectiveMaxTokens, budget + 4096));
            if (!omitTemperature) params.put("temperature", 1.0);
        } else if (!omitTemperature) {
            params.put("temperature", temperature);
        }

        // Tools
        if (anthropicTools != null && !anthropicTools.isEmpty()) {
            params.put("tools", anthropicTools);
            Map<String, Object> tc = convertToolChoice(toolChoice, thinkingEnabled);
            if (tc != null) params.put("tool_choice", tc);
        }

        return params;
    }

    /**
     * Build Anthropic SDK MessageParam objects from converted message maps.
     * Uses the anthropic-java SDK builder pattern for type-safe message creation.
     */
    @SuppressWarnings("unchecked")
    private List<MessageParam> buildMessageParams(List<Map<String, Object>> anthropicMsgs) {
        List<MessageParam> result = new ArrayList<>();
        for (Map<String, Object> msg : anthropicMsgs) {
            String role = (String) msg.get("role");
            List<Map<String, Object>> contentBlocks =
                (List<Map<String, Object>>) msg.get("content");

            List<ContentBlockParam> blocks = new ArrayList<>();
            if (contentBlocks != null) {
                for (Map<String, Object> block : contentBlocks) {
                    blocks.add(buildContentBlock(block));
                }
            }

            ContentBlockParam[] blockArray = blocks.toArray(new ContentBlockParam[0]);

            if ("user".equals(role)) {
                result.add(MessageParam.ofUser(UserMessageParam.builder()
                    .content(blockArray)
                    .build()));
            } else if ("assistant".equals(role)) {
                result.add(MessageParam.ofAssistant(AssistantMessageParam.builder()
                    .content(blockArray)
                    .build()));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ContentBlockParam buildContentBlock(Map<String, Object> block) {
        String type = (String) block.get("type");
        return switch (type) {
            case "text" -> ContentBlockParam.ofText(TextBlockParam.builder()
                .text((String) block.getOrDefault("text", ""))
                .build());
            case "tool_use" -> ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                .id((String) block.get("id"))
                .name((String) block.get("name"))
                .input(JsonValue.from(block.get("input")))
                .build());
            case "tool_result" -> ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId((String) block.get("tool_use_id"))
                .content((String) block.getOrDefault("content", ""))
                .build());
            case "image" -> ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(buildImageSource((Map<String, Object>) block.get("source")))
                .build());
            default -> ContentBlockParam.ofText(TextBlockParam.builder()
                .text(String.valueOf(block))
                .build());
        };
    }

    @SuppressWarnings("unchecked")
    private ImageBlockParam.Source buildImageSource(Map<String, Object> source) {
        String sourceType = (String) source.get("type");
        if ("base64".equals(sourceType)) {
            return ImageBlockParam.Source.ofBase64Source(Base64ImageSource.builder()
                .mediaType((String) source.get("media_type"))
                .data((String) source.get("data"))
                .build());
        } else {
            return ImageBlockParam.Source.ofUrlImageSource(UrlImageSource.builder()
                .url((String) source.get("url"))
                .build());
        }
    }

    // =========================================================================
    // Response parsing
    // =========================================================================

    static LLMResponse parseResponse(Message response) {
        StringBuilder contentParts = new StringBuilder();
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        List<Map<String, Object>> thinkingBlocks = new ArrayList<>();

        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                contentParts.append(block.asText().text());
            } else if (block.isToolUse()) {
                ToolUseBlock tu = block.asToolUse();
                toolCalls.add(new ToolCallRequest(
                    tu.id(), tu.name(), tu.input(), null, null, null));
            } else if (block.isThinking()) {
                ThinkingBlock thinking = block.asThinking();
                Map<String, Object> tb = new LinkedHashMap<>();
                tb.put("type", "thinking");
                tb.put("thinking", thinking.thinking());
                tb.put("signature", thinking.signature());
                thinkingBlocks.add(tb);
            }
        }

        Map<String, String> stopMap = Map.of(
            "tool_use", "tool_calls", "end_turn", "stop", "max_tokens", "length");
        String finishReason = stopMap.getOrDefault(
            (response.stopReason() != null) ? response.stopReason().toString() : "",
            "stop");

        Map<String, Integer> usage = Map.of();
        if (response.usage() != null) {
            Usage u = response.usage();
            int inputTokens = u.inputTokens();
            int cacheCreation = u.cacheCreationInputTokens() != null
                ? u.cacheCreationInputTokens() : 0;
            int cacheRead = u.cacheReadInputTokens() != null
                ? u.cacheReadInputTokens() : 0;
            int totalPrompt = inputTokens + cacheCreation + cacheRead;
            usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", totalPrompt);
            usage.put("completion_tokens", u.outputTokens());
            usage.put("total_tokens", totalPrompt + u.outputTokens());
            if (cacheCreation > 0) usage.put("cache_creation_input_tokens", cacheCreation);
            if (cacheRead > 0) {
                usage.put("cache_read_input_tokens", cacheRead);
                usage.put("cached_tokens", cacheRead);
            }
        }

        return new LLMResponse(
            !contentParts.isEmpty() ? contentParts.toString() : null,
            toolCalls, finishReason, usage,
            null, null,
            thinkingBlocks.isEmpty() ? null : thinkingBlocks,
            null, null, null, null, null, null);
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    static LLMResponse handleError(Exception e) {
        String msg;
        Double retryAfter = null;
        Integer statusCode = null;
        String errorKind = null;
        Boolean shouldRetry = null;

        // Extract from Anthropic SDK exception (AnthropicException, etc.)
        // The SDK may expose getStatusCode(), getResponseHeaders(), etc.
        try {
            java.lang.reflect.Method statusMethod = e.getClass().getMethod("statusCode");
            statusCode = (Integer) statusMethod.invoke(e);
        } catch (Exception ignored) {}

        msg = "Error: " + e.getMessage();
        if (msg.length() > 500) msg = msg.substring(0, 500);

        String errorName = e.getClass().getSimpleName().toLowerCase();
        if (errorName.contains("timeout")) errorKind = "timeout";
        else if (errorName.contains("connection")) errorKind = "connection";

        retryAfter = extractRetryAfter(msg);
        String[] typeCode = extractErrorTypeCode(e.getMessage());
        String errorType = typeCode[0];
        String errorCode = typeCode[1];

        return new LLMResponse(
            msg, "error", statusCode, errorKind, errorType, errorCode,
            retryAfter, shouldRetry);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    static String stripPrefix(String model) {
        if (model != null && model.startsWith("anthropic/")) {
            return model.substring("anthropic/".length());
        }
        return model;
    }

    private static boolean isStreamingRequiredError(Exception e) {
        String msg = e.getMessage();
        return msg != null && msg.toLowerCase().contains("streaming is required");
    }

    @Override
    @SuppressWarnings("unchecked")
    public LLMResponse chat(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String model,
        int maxTokens,
        double temperature,
        String reasoningEffort,
        Object toolChoice
    ) {
        Map<String, Object> params = buildKwargs(
            messages, tools, model, maxTokens, temperature, reasoningEffort,
            toolChoice, true);

        try {
            // Build SDK request
            MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model((String) params.get("model"))
                .maxTokens((Integer) params.get("max_tokens"));

            if (params.containsKey("system")) {
                // Cast and set system
                Object sys = params.get("system");
                if (sys instanceof String s) builder.system(s);
                else builder.system((List<SystemTextBlockParam>) sys);  // cast
            }

            builder.messages((List<MessageParam>) params.get("messages"));

            if (params.containsKey("temperature")) {
                builder.temperature(((Number) params.get("temperature")).doubleValue());
            }
            if (params.containsKey("thinking")) {
                // builder.thinking(...) — SDK type
            }
            if (params.containsKey("tools")) {
                // builder.tools(...)
            }

            Message response = client.messages().create(builder.build());
            return parseResponse(response);

        } catch (Exception e) {
            if (isStreamingRequiredError(e)) {
                // Transparently retry via streaming path
                return chatStream(messages, tools, model, maxTokens, temperature,
                                 reasoningEffort, toolChoice, null, null, null);
            }
            return handleError(e);
        }
    }

    @Override
    public LLMResponse chatStream(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String model,
        int maxTokens,
        double temperature,
        String reasoningEffort,
        Object toolChoice,
        ContentDeltaCallback onContentDelta,
        ContentDeltaCallback onThinkingDelta,
        ToolCallDeltaCallback onToolCallDelta
    ) {
        Map<String, Object> params = buildKwargs(
            messages, tools, model, maxTokens, temperature, reasoningEffort,
            toolChoice, true);

        int idleTimeoutS = Integer.parseInt(
            System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));

        try {
            MessageCreateParams.Builder builder = buildStreamParams(params);
            builder.stream(true);

            StreamResponse<MessageStreamEvent> stream = client.messages()
                .createStreaming(builder.build());

            // Track tool call buffers during stream
            Map<Integer, Map<String, String>> toolBlocks = new HashMap<>();
            List<ContentBlock> finalContent = new ArrayList<>();

            // Iterate stream events (blocking — virtual thread handles I/O wait)
            stream.stream().forEach(event -> {
                if (event.isContentBlockStart()) {
                    ContentBlockStartEvent startEvent = event.asContentBlockStart();
                    ContentBlock block = startEvent.contentBlock();
                    if (block.isToolUse()) {
                        ToolUseBlock tu = block.asToolUse();
                        int index = startEvent.index();
                        toolBlocks.put(index, Map.of(
                            "call_id", tu.id(), "name", tu.name()));
                        if (onToolCallDelta != null) {
                            onToolCallDelta.accept(Map.of(
                                "index", index, "call_id", tu.id(),
                                "name", tu.name(), "arguments_delta", ""));
                        }
                    }
                } else if (event.isContentBlockDelta()) {
                    ContentBlockDeltaEvent deltaEvent = event.asContentBlockDelta();
                    Delta delta = deltaEvent.delta();
                    if (delta.isThinkingDelta() && onThinkingDelta != null) {
                        onThinkingDelta.accept(delta.asThinkingDelta().thinking());
                    } else if (delta.isTextDelta() && onContentDelta != null) {
                        onContentDelta.accept(delta.asTextDelta().text());
                    } else if (delta.isInputJsonDelta() && onToolCallDelta != null) {
                        int index = deltaEvent.index();
                        Map<String, String> state = toolBlocks.getOrDefault(index, Map.of());
                        onToolCallDelta.accept(Map.of(
                            "index", index,
                            "call_id", state.getOrDefault("call_id", ""),
                            "name", state.getOrDefault("name", ""),
                            "arguments_delta", delta.asInputJsonDelta().partialJson()));
                    }
                }
                // Accumulate blocks for final message reconstruction
            });

            // Get final message (timeout-protected)
            // In practice, get the accumulated message from the stream
            Message finalMessage = stream.getFinalMessage(Duration.ofSeconds(idleTimeoutS));
            return parseResponse(finalMessage);

        } catch (Exception e) {
            return handleError(e);
        }
    }

    private MessageCreateParams.Builder buildStreamParams(Map<String, Object> params) {
        // Build from params map — similar to chat() builder above
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
            .model((String) params.get("model"))
            .maxTokens((Integer) params.get("max_tokens"));
        // ... (same as chat builder)
        return builder;
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    private static String genToolId() {
        StringBuilder sb = new StringBuilder("toolu_");
        for (int i = 0; i < 22; i++) {
            sb.append(ALPHANUM.charAt(RANDOM.nextInt(ALPHANUM.length())));
        }
        return sb.toString();
    }
}
```

### 2. `OpenAiCompatProvider.java` — 使用 `openai-java` SDK

```java
// com.nanobot.providers.impl.OpenAiCompatProvider.java
package com.nanobot.providers.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.*;
import com.openai.client.OpenAiClient;
import com.openai.client.OkHttpOpenAiClient;
import com.openai.models.chat.completions.*;
import com.openai.models.responses.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Unified provider for all OpenAI-compatible APIs.
 *
 * Key design choices for Java port:
 * - Lazy client init (openai-java OkHttp client is heavyweight)
 * - Dual API backends: Chat Completions AND Responses API
 * - Responses API circuit breaker
 * - 30+ provider-specific thinking style injections via extra_body
 * - All calls are synchronous (virtual threads handle I/O blocking)
 */
public class OpenAiCompatProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ALLOWED_MSG_KEYS = Set.of(
        "role", "content", "tool_calls", "tool_call_id", "name",
        "reasoning_content", "extra_content");
    private static final double REQUEST_TIMEOUT_S = 120.0;

    // Responses API circuit breaker
    private static final int RESPONSES_FAILURE_THRESHOLD = 3;
    private static final int RESPONSES_PROBE_INTERVAL_S = 300;  // 5 minutes

    private final String defaultModel;
    private final Map<String, String> extraHeaders;
    private final ProviderSpec spec;
    private final Map<String, Object> extraBody;
    private final String apiType;
    private final Map<String, String> extraQuery;
    private final String effectiveBase;
    private final boolean isLocal;

    // Lazy-init client
    private volatile OpenAiClient client;
    private final ReentrantLock clientLock = new ReentrantLock();

    // Responses API circuit breaker state
    private final Map<String, Integer> responsesFailures = new HashMap<>();
    private final Map<String, Long> responsesTrippedAt = new HashMap<>();

    // Thinking style maps (mirrors Python _THINKING_STYLE_MAP / _GATEWAY_REASONING_STYLE_MAP)
    private static final Map<String, String> KIMI_THINKING_MODELS = Set.of(
        "kimi-k2.5", "kimi-k2.6", "k2.6-code-preview");
    private static final Map<String, String> MIMO_THINKING_MODELS = Set.of(
        "mimo-v2.5-pro", "mimo-v2.5", "mimo-v2-pro", "mimo-v2-omni");

    public OpenAiCompatProvider(
        String apiKey, String apiBase, String defaultModel,
        Map<String, String> extraHeaders, ProviderSpec spec,
        Map<String, Object> extraBody, String apiType,
        Map<String, String> extraQuery
    ) {
        super(apiKey, apiBase);
        this.defaultModel = (defaultModel != null) ? defaultModel : "gpt-4o";
        this.extraHeaders = (extraHeaders != null) ? new LinkedHashMap<>(extraHeaders) : new LinkedHashMap<>();
        this.spec = spec;
        this.extraBody = (extraBody != null) ? new LinkedHashMap<>(extraBody) : new LinkedHashMap<>();
        this.apiType = ("openai".equals(spec != null ? spec.name() : null)) ? apiType : "auto";
        this.extraQuery = (extraQuery != null) ? new LinkedHashMap<>(extraQuery) : new LinkedHashMap<>();

        // Determine effective base URL
        this.effectiveBase = (apiBase != null) ? apiBase
            : (spec != null && spec.defaultApiBase() != null && !spec.defaultApiBase().isEmpty())
                ? spec.defaultApiBase() : null;

        // Detect local endpoint
        this.isLocal = spec != null && spec.isLocal();

        // Setup env vars for gateway providers
        if (apiKey != null && spec != null && spec.envKey() != null && !spec.envKey().isEmpty()) {
            setupEnv(apiKey, apiBase);
        }
    }

    /** Lazy client creation — the OkHttp transport is expensive (~700ms on Windows). */
    private OpenAiClient ensureClient() {
        if (client != null) return client;
        clientLock.lock();
        try {
            if (client != null) return client;
            OkHttpOpenAiClient.Builder builder = OkHttpOpenAiClient.builder()
                .maxRetries(0)  // centralized in LLMProvider.runWithRetry
                .timeout(Duration.ofSeconds((long) REQUEST_TIMEOUT_S));

            if (apiKey != null) builder.apiKey(apiKey);
            if (effectiveBase != null) builder.baseUrl(effectiveBase);

            // For local endpoints, disable HTTP keepalive to avoid dead connection reuse
            // (The OkHttp client should be configured with connection pool limits)

            client = builder.build();
            return client;
        } finally {
            clientLock.unlock();
        }
    }

    private void setupEnv(String apiKey, String apiBase) {
        if (spec == null) return;
        String effectiveBase = (apiBase != null) ? apiBase : spec.defaultApiBase();
        // Set env vars for child processes (shell, etc.)
        System.setProperty(spec.envKey(), apiKey);
        for (Map.Entry<String, String> extra : spec.envExtras()) {
            String resolved = extra.getValue()
                .replace("{api_key}", apiKey)
                .replace("{api_base}", effectiveBase != null ? effectiveBase : "");
            System.setProperty(extra.getKey(), resolved);
        }
    }

    // =========================================================================
    // Sanitize messages (provider-specific rules)
    // =========================================================================

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> sanitizeMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> sanitized = sanitizeRequestMessages(messages, ALLOWED_MSG_KEYS);
        boolean normalizeToolIds = (spec != null && "mistral".equals(spec.name()));
        boolean forceStringContent = (spec != null && "deepseek".equals(spec.name()));

        Map<String, String> idMap = new HashMap<>();
        Map<String, Deque<String>> pendingToolIds = new HashMap<>();

        for (Map<String, Object> clean : sanitized) {
            // Normalize tool call IDs
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) clean.get("tool_calls");
            if (toolCalls != null) {
                Set<String> usedIds = new HashSet<>();
                List<Map<String, Object>> normalized = new ArrayList<>();
                for (int i = 0; i < toolCalls.size(); i++) {
                    Map<String, Object> tc = toolCalls.get(i);
                    if (!(tc instanceof Map)) { normalized.add(tc); continue; }

                    Map<String, Object> tcClean = new LinkedHashMap<>(tc);
                    String rawId = (String) tcClean.get("id");
                    String mappedId = uniqueToolId(rawId, usedIds, i, normalizeToolIds, idMap);
                    tcClean.put("id", mappedId);
                    usedIds.add(mappedId);

                    if (rawId != null && !rawId.isEmpty()) {
                        pendingToolIds.computeIfAbsent(rawId, k -> new ArrayDeque<>()).add(mappedId);
                    }

                    // Stringify function.arguments
                    Map<String, Object> function = (Map<String, Object>) tcClean.get("function");
                    if (function != null) {
                        Map<String, Object> funcClean = new LinkedHashMap<>(function);
                        if (funcClean.containsKey("arguments")) {
                            funcClean.put("arguments",
                                ToolArguments.toolArgumentsJsonForReplay(funcClean.get("arguments")));
                        } else {
                            funcClean.put("arguments", "{}");
                        }
                        tcClean.put("function", funcClean);
                    }
                    normalized.add(tcClean);
                }
                clean.put("tool_calls", normalized);
                if ("assistant".equals(clean.get("role"))) {
                    clean.put("content", null);
                }
            }

            // Map pending tool_result IDs
            if (clean.containsKey("tool_call_id")) {
                String originalId = (String) clean.get("tool_call_id");
                if (originalId != null) {
                    Deque<String> queue = pendingToolIds.get(originalId);
                    if (queue != null && !queue.isEmpty()) {
                        clean.put("tool_call_id", queue.pollFirst());
                        if (queue.isEmpty()) pendingToolIds.remove(originalId);
                    } else if (normalizeToolIds) {
                        clean.put("tool_call_id", idMap.computeIfAbsent(originalId,
                            k -> normalizeToolCallId(k)));
                    }
                }
            }

            // Force string content for DeepSeek
            if (forceStringContent
                && !("assistant".equals(clean.get("role"))
                     && clean.get("tool_calls") instanceof List<?> l && !l.isEmpty())) {
                clean.put("content", coerceContentToString(clean.get("content")));
            }
        }

        return enforceRoleAlternation(sanitized);
    }

    static String normalizeToolCallId(String id) {
        if (id == null) return null;
        if (id.length() == 9 && id.chars().allMatch(Character::isLetterOrDigit)) return id;
        // SHA-1 hash, first 9 chars
        try {
            java.security.MessageDigest sha1 = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = sha1.digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.substring(0, 9);
        } catch (Exception e) {
            return id.substring(0, Math.min(9, id.length()));
        }
    }

    // ... (uniqueToolId, coerceContentToString — similar to Python)

    // =========================================================================
    // Build Chat Completions kwargs
    // =========================================================================

    @SuppressWarnings("unchecked")
    Map<String, Object> buildKwargs(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        String model, int maxTokens, double temperature,
        String reasoningEffort, Object toolChoice
    ) {
        String modelName = (model != null) ? model : defaultModel;

        // Apply prompt caching for gateway providers with Claude models
        if (spec != null && spec.supportsPromptCaching()) {
            if (modelName.toLowerCase().startsWith("anthropic/")
                || modelName.toLowerCase().startsWith("claude")) {
                // applyCacheControl would go here (omitted for brevity)
            }
        }

        // Strip model prefix
        if (spec != null && spec.stripModelPrefix()) {
            int lastSlash = modelName.lastIndexOf('/');
            if (lastSlash >= 0) modelName = modelName.substring(lastSlash + 1);
        }

        Map<String, Object> kwargs = new LinkedHashMap<>();
        kwargs.put("model", modelName);
        kwargs.put("messages", sanitizeMessages(sanitizeEmptyContent(messages)));

        // Temperature: skip for reasoning models with active reasoning
        if (supportsTemperature(modelName, reasoningEffort)) {
            kwargs.put("temperature", temperature);
        }

        // max_tokens vs max_completion_tokens
        boolean useMaxCompletionTokens = (spec != null && spec.supportsMaxCompletionTokens())
            || requiresMaxCompletionTokens(modelName);
        if (useMaxCompletionTokens) {
            kwargs.put("max_completion_tokens", Math.max(1, maxTokens));
        } else {
            kwargs.put("max_tokens", Math.max(1, maxTokens));
        }

        // Model overrides
        if (spec != null) {
            String modelLower = modelName.toLowerCase();
            for (Map.Entry<String, Map<String, Object>> override : spec.modelOverrides()) {
                if (modelLower.contains(override.getKey())) {
                    kwargs.putAll(override.getValue());
                    break;
                }
            }
        }

        // Reasoning effort + thinking style injection
        String semanticEffort = null;
        if (reasoningEffort instanceof String s) {
            semanticEffort = s.toLowerCase();
            if ("minimum".equals(semanticEffort)) semanticEffort = "minimal";
        }

        String wireEffort = reasoningEffort;
        if (spec != null && "dashscope".equals(spec.name()) && "minimal".equals(semanticEffort)) {
            wireEffort = "minimum";  // DashScope alias
        }

        if (wireEffort != null && !"none".equalsIgnoreCase(semanticEffort)) {
            kwargs.put("reasoning_effort", wireEffort);
        }

        // Thinking style injection via extra_body
        if (reasoningEffort != null) {
            boolean thinkingEnabled = !("none".equals(semanticEffort) || "minimal".equals(semanticEffort));
            List<String> thinkingStyles = thinkingStylesFor(spec, modelName);
            for (String style : thinkingStyles) {
                Map<String, Object> extra = thinkingExtraBody(style, thinkingEnabled);
                if (extra != null) {
                    kwargs.computeIfAbsent("extra_body", k -> new LinkedHashMap<>())
                          .putAll(extra);
                }
            }

            // Gateway reasoning style
            if (spec != null && spec.gatewayReasoningStyle() != null
                && !spec.gatewayReasoningStyle().isEmpty()
                && modelThinkingStyle(modelName) != null) {
                Map<String, Object> extra = gatewayReasoningExtraBody(
                    spec.gatewayReasoningStyle(), semanticEffort);
                if (extra != null) {
                    kwargs.computeIfAbsent("extra_body", k -> new LinkedHashMap<>())
                          .putAll(extra);
                }
            }

            // Kimi: drop redundant reasoning_effort
            if (KIMI_THINKING_MODELS.contains(modelSlug(modelName))) {
                kwargs.remove("reasoning_effort");
            }
        }

        // Tools
        if (tools != null && !tools.isEmpty()) {
            kwargs.put("tools", tools);
            kwargs.put("tool_choice", (toolChoice != null) ? toolChoice : "auto");
        }

        // reasoning_content="" backfill for DeepSeek thinking mode
        boolean explicitThinking = reasoningEffort != null
            && !"none".equals(semanticEffort) && !"minimal".equals(semanticEffort)
            && ((spec != null && spec.thinkingStyle() != null && !spec.thinkingStyle().isEmpty())
                || modelThinkingStyle(modelName) != null);
        boolean implicitDeepseekThinking = spec != null && "deepseek".equals(spec.name())
            && reasoningEffort != null
            && !"none".equals(semanticEffort) && !"minimal".equals(semanticEffort)
            && !"minimum".equals(semanticEffort)
            && (modelName.contains("deepseek-v4") || modelName.contains("deepseek-reasoner"));
        if (explicitThinking || implicitDeepseekThinking) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) kwargs.get("messages");
            if (msgs != null) {
                for (Map<String, Object> msg : msgs) {
                    if ("assistant".equals(msg.get("role"))
                        && !msg.containsKey("reasoning_content")) {
                        msg.put("reasoning_content", "");
                    }
                }
            }
        }

        // Merge user extra_body (deep merge)
        if (!this.extraBody.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> existing = (Map<String, Object>) kwargs.getOrDefault(
                "extra_body", new LinkedHashMap<>());
            kwargs.put("extra_body", deepMerge(existing, this.extraBody));
        }

        return kwargs;
    }

    // --- Thinking helpers (mirrors Python functions) ---

    static List<String> thinkingStylesFor(ProviderSpec spec, String modelName) {
        List<String> styles = new ArrayList<>();
        if (spec != null && spec.thinkingStyle() != null && !spec.thinkingStyle().isEmpty()) {
            styles.add(spec.thinkingStyle());
        }
        String modelStyle = modelThinkingStyle(modelName);
        if (modelStyle != null && !styles.contains(modelStyle)) {
            styles.add(modelStyle);
        }
        return styles;
    }

    static String modelThinkingStyle(String modelName) {
        String slug = modelSlug(modelName);
        if (KIMI_THINKING_MODELS.contains(slug)) return "thinking_type";
        if (MIMO_THINKING_MODELS.contains(slug)) return "thinking_type";
        return null;
    }

    static String modelSlug(String modelName) {
        if (modelName == null) return "";
        int lastSlash = modelName.lastIndexOf('/');
        return (lastSlash >= 0) ? modelName.substring(lastSlash + 1).toLowerCase()
            : modelName.toLowerCase();
    }

    static Map<String, Object> thinkingExtraBody(String style, boolean thinkingEnabled) {
        return switch (style) {
            case "thinking_type" -> Map.of("thinking",
                Map.of("type", thinkingEnabled ? "enabled" : "disabled"));
            case "enable_thinking" -> Map.of("enable_thinking", thinkingEnabled);
            case "reasoning_split" -> Map.of("reasoning_split", thinkingEnabled);
            default -> null;
        };
    }

    static Map<String, Object> gatewayReasoningExtraBody(String style, String effort) {
        if (effort == null) return null;
        if ("reasoning_effort".equals(style)) {
            return Map.of("reasoning", Map.of("effort", effort));
        }
        return null;
    }

    static boolean supportsTemperature(String modelName, String reasoningEffort) {
        if (reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort)) return false;
        String name = modelName.toLowerCase();
        return !(name.contains("gpt-5") || name.contains("o1")
              || name.contains("o3") || name.contains("o4"));
    }

    static boolean requiresMaxCompletionTokens(String modelName) {
        String slug = modelSlug(modelName);
        return slug.contains("gpt-5")
            || slug.startsWith("o1") || slug.startsWith("o3") || slug.startsWith("o4");
    }

    // =========================================================================
    // Responses API circuit breaker
    // =========================================================================

    boolean shouldUseResponsesApi(String model, String reasoningEffort) {
        if ("chat_completions".equals(apiType)) return false;
        if (spec != null && !"openai".equals(spec.name())
            && !"github_copilot".equals(spec.name())) return false;
        if ("responses".equals(apiType)) return true;  // explicit, ignore breaker

        // Only for direct OpenAI endpoints
        if (spec == null || !"github_copilot".equals(spec.name())) {
            if (!isDirectOpenAiBase(effectiveBase)) return false;
        }

        String modelName = (model != null ? model : defaultModel).toLowerCase();
        boolean wants = false;
        if (reasoningEffort != null && !"none".equalsIgnoreCase(reasoningEffort)) wants = true;
        else if (modelName.contains("gpt-5") || modelName.contains("o1")
              || modelName.contains("o3") || modelName.contains("o4")) wants = true;
        if (!wants) return false;

        return responsesCircuitAllowsProbe(model, reasoningEffort);
    }

    boolean responsesCircuitAllowsProbe(String model, String reasoningEffort) {
        String key = responsesCircuitKey(model, reasoningEffort);
        int failures = responsesFailures.getOrDefault(key, 0);
        if (failures >= RESPONSES_FAILURE_THRESHOLD) {
            long tripped = responsesTrippedAt.getOrDefault(key, 0L);
            if ((System.currentTimeMillis() - tripped) < RESPONSES_PROBE_INTERVAL_S * 1000L) {
                return false;  // circuit open
            }
        }
        return true;  // closed or half-open
    }

    void recordResponsesFailure(String model, String reasoningEffort) {
        String key = responsesCircuitKey(model, reasoningEffort);
        int count = responsesFailures.merge(key, 1, Integer::sum);
        if (count >= RESPONSES_FAILURE_THRESHOLD) {
            responsesTrippedAt.put(key, System.currentTimeMillis());
            log.warn("Responses API circuit open for {} — falling back to Chat Completions", key);
        }
    }

    void recordResponsesSuccess(String model, String reasoningEffort) {
        String key = responsesCircuitKey(model, reasoningEffort);
        responsesFailures.remove(key);
        responsesTrippedAt.remove(key);
    }

    static String responsesCircuitKey(String model, String reasoningEffort) {
        String mn = (model != null ? model : "default").toLowerCase();
        String re = (reasoningEffort != null) ? reasoningEffort.toLowerCase() : "";
        return mn + ":" + re;
    }

    static boolean isDirectOpenAiBase(String apiBase) {
        if (apiBase == null) return true;
        String normalized = apiBase.strip().toLowerCase().replaceAll("/+$", "");
        return normalized.contains("api.openai.com") && !normalized.contains("openrouter");
    }

    // =========================================================================
    // Response parsing
    // =========================================================================

    @SuppressWarnings("unchecked")
    static LLMResponse parseResponse(Map<String, Object> responseMap) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseMap.get("choices");
        if (choices == null || choices.isEmpty()) {
            return new LLMResponse("Error: API returned empty choices.", "error");
        }

        Map<String, Object> choice0 = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice0.get("message");
        if (message == null) {
            return new LLMResponse("Error: API returned empty choices.", "error");
        }

        String content = extractTextContent(message.get("content"));
        String finishReason = (String) choice0.getOrDefault("finish_reason", "stop");

        // Aggregate tool calls across all choices
        List<ToolCallRequest> toolCalls = new ArrayList<>();
        for (Map<String, Object> ch : choices) {
            Map<String, Object> m = (Map<String, Object>) ch.get("message");
            if (m == null) continue;
            List<Map<String, Object>> tcList = (List<Map<String, Object>>) m.get("tool_calls");
            if (tcList != null) {
                for (Map<String, Object> tc : tcList) {
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                    ToolCallRequest tcr = new ToolCallRequest(
                        (String) tc.getOrDefault("id", shortToolId()),
                        (String) (fn != null ? fn.getOrDefault("name", "") : ""),
                        ToolArguments.parseToolArguments(
                            fn != null ? fn.get("arguments") : null),
                        null, null, null);
                    toolCalls.add(tcr);
                }
                if ("tool_calls".equals(ch.get("finish_reason"))
                    || "stop".equals(ch.get("finish_reason"))) {
                    finishReason = (String) ch.get("finish_reason");
                }
            }
            if (content == null) content = extractTextContent(m.get("content"));
        }

        // Usage extraction with cached_tokens normalization
        Map<String, Integer> usage = extractUsage(responseMap);

        // Reasoning content
        String reasoningContent = (String) message.get("reasoning_content");
        if (reasoningContent == null && message.get("reasoning") instanceof String r) {
            reasoningContent = r;
        }

        return new LLMResponse(
            content,
            toolCalls,
            finishReason,
            usage,
            null,
            reasoningContent,
            null,
            null, null, null, null, null, null);
    }

    static String extractTextContent(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof List<?> items) {
            StringBuilder sb = new StringBuilder();
            for (Object item : items) {
                if (item instanceof Map<?, ?> m && m.get("text") instanceof String t) {
                    sb.append(t);
                } else if (item instanceof String s) {
                    sb.append(s);
                }
            }
            return !sb.isEmpty() ? sb.toString() : null;
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Integer> extractUsage(Map<String, Object> responseMap) {
        Map<String, Object> usageObj = (Map<String, Object>) responseMap.get("usage");
        if (usageObj == null) return Map.of();

        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("prompt_tokens", toInt(usageObj.get("prompt_tokens")));
        result.put("completion_tokens", toInt(usageObj.get("completion_tokens")));
        result.put("total_tokens", toInt(usageObj.get("total_tokens")));

        // Normalize cached_tokens (priority chain across providers)
        Object details = usageObj.get("prompt_tokens_details");
        if (details instanceof Map<?, ?> d
            && ((Map<String, Object>) d).get("cached_tokens") instanceof Integer ct && ct > 0) {
            result.put("cached_tokens", ct);
        } else if (usageObj.get("cached_tokens") instanceof Integer ct && ct > 0) {
            result.put("cached_tokens", ct);
        } else if (usageObj.get("prompt_cache_hit_tokens") instanceof Integer pch && pch > 0) {
            result.put("cached_tokens", pch);
        }

        return result;
    }

    // =========================================================================
    // Error handling
    // =========================================================================

    static LLMResponse handleError(Exception e, ProviderSpec spec, String apiBase) {
        String bodyText = e.getMessage();
        if (bodyText == null) bodyText = "";
        String msg = "Error: " + bodyText.strip().substring(0, Math.min(500, bodyText.strip().length()));

        // Hint for local endpoints
        if (spec != null && spec.isLocal()) {
            String text = (bodyText + " " + e.getClass().getName()).toLowerCase();
            if (text.contains("502") || text.contains("connection") || text.contains("refused")) {
                msg += "\nHint: this is a local model endpoint. Check that the local server "
                     + "is reachable at " + (apiBase != null ? apiBase : spec.defaultApiBase());
            }
        }

        // Extract structured error metadata
        Integer statusCode = null;
        String errorKind = null;
        Double retryAfter = null;
        Boolean shouldRetry = null;
        String errorType = null;
        String errorCode = null;

        // Try reflection to extract status code from exception
        try {
            java.lang.reflect.Method scMethod = e.getClass().getMethod("statusCode");
            statusCode = (Integer) scMethod.invoke(e);
        } catch (Exception ignored) {}

        String errorName = e.getClass().getSimpleName().toLowerCase();
        if (errorName.contains("timeout")) errorKind = "timeout";
        else if (errorName.contains("connection")) errorKind = "connection";

        retryAfter = extractRetryAfter(msg);
        String[] typeCode = extractErrorTypeCode(e.getMessage());
        errorType = typeCode[0];
        errorCode = typeCode[1];

        return new LLMResponse(
            msg, "error", statusCode, errorKind, errorType, errorCode,
            retryAfter, shouldRetry);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    @Override
    public LLMResponse chat(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        String model, int maxTokens, double temperature,
        String reasoningEffort, Object toolChoice
    ) {
        ensureClient();
        try {
            if (shouldUseResponsesApi(model, reasoningEffort)) {
                try {
                    // Build and call Responses API (similar to _build_responses_body)
                    LLMResponse result = chatViaResponsesApi(
                        messages, tools, model, maxTokens, temperature,
                        reasoningEffort, toolChoice);
                    recordResponsesSuccess(model, reasoningEffort);
                    return result;
                } catch (Exception responsesError) {
                    if (spec != null && "github_copilot".equals(spec.name())) throw responsesError;
                    if ("responses".equals(apiType)) throw responsesError;
                    if (!shouldFallbackFromResponsesError(responsesError)) throw responsesError;
                    recordResponsesFailure(model, reasoningEffort);
                }
            }

            // Chat Completions API
            Map<String, Object> kwargs = buildKwargs(
                messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);

            // Build SDK parameters and call
            ChatCompletionCreateParams.Builder cpBuilder = buildChatCompletionParams(kwargs);
            ChatCompletion completion = client.chat().completions()
                .create(cpBuilder.build());

            // Convert SDK response to Map for shared parseResponse
            Map<String, Object> responseMap = convertSdkResponse(completion);
            return parseResponse(responseMap);

        } catch (Exception e) {
            return handleError(e, spec, apiBase);
        }
    }

    @Override
    public LLMResponse chatStream(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        String model, int maxTokens, double temperature,
        String reasoningEffort, Object toolChoice,
        ContentDeltaCallback onContentDelta,
        ContentDeltaCallback onThinkingDelta,
        ToolCallDeltaCallback onToolCallDelta
    ) {
        ensureClient();
        int idleTimeoutS = Integer.parseInt(
            System.getenv().getOrDefault("NANOBOT_STREAM_IDLE_TIMEOUT_S", "90"));

        try {
            if (shouldUseResponsesApi(model, reasoningEffort)) {
                try {
                    LLMResponse result = chatStreamViaResponsesApi(
                        messages, tools, model, maxTokens, temperature,
                        reasoningEffort, toolChoice,
                        onContentDelta, onThinkingDelta, onToolCallDelta);
                    recordResponsesSuccess(model, reasoningEffort);
                    return result;
                } catch (Exception responsesError) {
                    if (spec != null && "github_copilot".equals(spec.name())) throw responsesError;
                    if ("responses".equals(apiType)) throw responsesError;
                    if (!shouldFallbackFromResponsesError(responsesError)) throw responsesError;
                    recordResponsesFailure(model, reasoningEffort);
                }
            }

            Map<String, Object> kwargs = buildKwargs(
                messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);

            // Zhipu special: tool_stream
            if (spec != null && "zhipu".equals(spec.name())
                && tools != null && !tools.isEmpty() && onToolCallDelta != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> eb = (Map<String, Object>)
                    kwargs.computeIfAbsent("extra_body", k -> new LinkedHashMap<>());
                eb.put("tool_stream", true);
            }

            kwargs.put("stream", true);
            kwargs.put("stream_options", Map.of("include_usage", true));

            // Stream the response
            // The openai-java SDK provides streaming via client.chat().completions().createStreaming()
            // In Java virtual threads: iterate the Stream<ChatCompletionChunk> synchronously
            List<Map<String, Object>> chunks = new ArrayList<>();

            ChatCompletionCreateParams.Builder cpBuilder = buildChatCompletionParams(kwargs);
            // stream() returns Iterable<ChatCompletionChunk>
            for (ChatCompletionChunk chunk :
                 client.chat().completions().createStreaming(cpBuilder.build()).stream()) {
                chunks.add(convertChunkToMap(chunk));

                if (!chunk.choices().isEmpty()) {
                    ChatCompletionChunk.Choice.Delta delta = chunk.choices().get(0).delta();

                    if (onContentDelta != null && delta.content() != null) {
                        onContentDelta.accept(delta.content().orElse(""));
                    }
                    if (onThinkingDelta != null) {
                        // reasoning_content is in delta as an extra field
                    }
                    if (onToolCallDelta != null) {
                        List<ChatCompletionChunk.Choice.Delta.ToolCall> tcList = delta.toolCalls().orElse(List.of());
                        for (int i = 0; i < tcList.size(); i++) {
                            ChatCompletionChunk.Choice.Delta.ToolCall tc = tcList.get(i);
                            onToolCallDelta.accept(Map.of(
                                "index", tc.index().orElse(i),
                                "call_id", tc.id().orElse(""),
                                "name", tc.function().flatMap(f -> f.name()).orElse(""),
                                "arguments_delta", tc.function().flatMap(f -> f.arguments()).orElse("")));
                        }
                    }
                }
            }

            return parseChunks(chunks);

        } catch (Exception e) {
            return handleError(e, spec, apiBase);
        }
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    // ... (helper methods for Responses API, chunk parsing, etc.)

    private ChatCompletionCreateParams.Builder buildChatCompletionParams(Map<String, Object> kwargs) {
        // Build typed SDK params from Map — omitted for brevity
        return ChatCompletionCreateParams.builder();
    }

    private Map<String, Object> convertSdkResponse(ChatCompletion completion) {
        // Convert SDK Pydantic model back to Map for shared parseResponse — omitted
        return Map.of();
    }

    private Map<String, Object> convertChunkToMap(ChatCompletionChunk chunk) {
        return Map.of();
    }

    static LLMResponse parseChunks(List<Map<String, Object>> chunks) {
        // Mirror Python _parse_chunks — accumulate deltas into final response
        return new LLMResponse("parsed", "stop");
    }

    private LLMResponse chatViaResponsesApi(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        String model, int maxTokens, double temperature,
        String reasoningEffort, Object toolChoice
    ) {
        // Build Responses API body using shared convert_messages / convert_tools
        // Call client.responses().create(...)
        // Return parsed LLMResponse
        throw new UnsupportedOperationException("Responses API — see 05-providers-impl.md");
    }

    private LLMResponse chatStreamViaResponsesApi(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        String model, int maxTokens, double temperature,
        String reasoningEffort, Object toolChoice,
        ContentDeltaCallback onContentDelta,
        ContentDeltaCallback onThinkingDelta,
        ToolCallDeltaCallback onToolCallDelta
    ) {
        throw new UnsupportedOperationException("Responses API streaming");
    }

    static boolean shouldFallbackFromResponsesError(Exception e) {
        // Check for Responses API-specific compatibility markers
        String msg = e.getMessage();
        if (msg == null) return false;
        msg = msg.toLowerCase();
        return msg.contains("responses") || msg.contains("response api")
            || msg.contains("max_output_tokens") || msg.contains("instructions")
            || msg.contains("unsupported") || msg.contains("not supported")
            || msg.contains("unknown parameter");
    }

    private static String shortToolId() {
        String alnum = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 9; i++) sb.append(alnum.charAt(rnd.nextInt(alnum.length())));
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepMerge(
        Map<String, Object> base, Map<String, Object> override
    ) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            if (merged.containsKey(entry.getKey())
                && merged.get(entry.getKey()) instanceof Map<?, ?> b
                && entry.getValue() instanceof Map<?, ?> o) {
                merged.put(entry.getKey(),
                    deepMerge((Map<String, Object>) b, (Map<String, Object>) o));
            } else {
                merged.put(entry.getKey(), entry.getValue());
            }
        }
        return merged;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        return 0;
    }
}
```

### 3. `FallbackProvider.java` — 带熔断器的故障自动转移

```java
// com.nanobot.providers.impl.FallbackProvider.java
package com.nanobot.providers.impl;

import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import com.nanobot.providers.ModelPreset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Function;

/**
 * Wraps a primary provider and transparently fails over to fallback models.
 *
 * Circuit breaker: 3 consecutive primary failures → 60s cooldown.
 * Non-fallbackable errors: auth, permission, content_filter, context_length, 400/401/403/404/422.
 */
public class FallbackProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackProvider.class);

    private static final int PRIMARY_FAILURE_THRESHOLD = 3;
    private static final long PRIMARY_COOLDOWN_MS = 60_000;  // 60 seconds
    private static final int SENTINEL = Integer.MIN_VALUE;

    private static final Set<String> FALLBACK_ERROR_KINDS = Set.of(
        "timeout", "connection", "server_error", "rate_limit", "overloaded");
    private static final Set<String> NON_FALLBACK_ERROR_KINDS = Set.of(
        "authentication", "auth", "permission", "content_filter",
        "refusal", "context_length", "invalid_request");

    private final LLMProvider primary;
    private final List<ModelPreset> fallbackPresets;
    private final Function<ModelPreset, LLMProvider> providerFactory;
    private final boolean hasFallbacks;

    // Circuit breaker state
    private int primaryFailures = 0;
    private Long primaryTrippedAt = null;  // System.currentTimeMillis() when tripped

    public FallbackProvider(
        LLMProvider primary,
        List<ModelPreset> fallbackPresets,
        Function<ModelPreset, LLMProvider> providerFactory
    ) {
        super(null, null);
        this.primary = primary;
        this.fallbackPresets = new ArrayList<>(fallbackPresets);
        this.providerFactory = providerFactory;
        this.hasFallbacks = !fallbackPresets.isEmpty();
    }

    public boolean supportsStreamRecoverCallback = true;

    @Override
    public GenerationSettings generation() {
        return primary.generation();
    }

    public void setGeneration(GenerationSettings gs) {
        primary.generation = gs;
    }

    @Override
    public String getDefaultModel() {
        return primary.getDefaultModel();
    }

    /** Is the primary provider currently available (not in cooldown)? */
    private boolean primaryAvailable() {
        if (primaryTrippedAt == null) return true;
        if (System.currentTimeMillis() - primaryTrippedAt >= PRIMARY_COOLDOWN_MS) {
            return true;  // half-open: allow one probe
        }
        return false;
    }

    @Override
    public LLMResponse chat(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        String model, int maxTokens, double temperature,
        String reasoningEffort, Object toolChoice
    ) {
        if (!hasFallbacks) return primary.chat(messages, tools, model, maxTokens,
                                               temperature, reasoningEffort, toolChoice);
        return tryWithFallback(
            (p, args) -> p.chat(
                args.messages, args.tools, args.model, args.maxTokens,
                args.temperature, args.reasoningEffort, args.toolChoice),
            new CallArgs(messages, tools, model, maxTokens, temperature,
                        reasoningEffort, toolChoice),
            null, null);
    }

    @Override
    public LLMResponse chatStream(
        List<Map<String, Object>> messages, List<Map<String, Object>> tools,
        String model, int maxTokens, double temperature,
        String reasoningEffort, Object toolChoice,
        ContentDeltaCallback onContentDelta,
        ContentDeltaCallback onThinkingDelta,
        ToolCallDeltaCallback onToolCallDelta
    ) {
        if (!hasFallbacks) return primary.chatStream(messages, tools, model, maxTokens,
            temperature, reasoningEffort, toolChoice,
            onContentDelta, onThinkingDelta, onToolCallDelta);

        boolean[] hasStreamed = {false};
        ContentDeltaCallback trackingDelta = text -> {
            if (text != null && !text.isEmpty()) hasStreamed[0] = true;
            if (onContentDelta != null) onContentDelta.accept(text);
        };

        return tryWithFallback(
            (p, args) -> p.chatStream(
                args.messages, args.tools, args.model, args.maxTokens,
                args.temperature, args.reasoningEffort, args.toolChoice,
                trackingDelta, onThinkingDelta, onToolCallDelta),
            new CallArgs(messages, tools, model, maxTokens, temperature,
                        reasoningEffort, toolChoice),
            hasStreamed, null);
    }

    /**
     * Try primary, then each fallback. Returns first successful response.
     */
    @FunctionalInterface
    private interface ProviderCall {
        LLMResponse apply(LLMProvider provider, CallArgs args);
    }

    private record CallArgs(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools,
        String model,
        int maxTokens,
        double temperature,
        String reasoningEffort,
        Object toolChoice
    ) {}

    private LLMResponse tryWithFallback(
        ProviderCall call,
        CallArgs args,
        boolean[] hasStreamed,
        Runnable onStreamRecover
    ) {
        String primaryModel = (args.model() != null)
            ? args.model() : primary.getDefaultModel();

        // Try primary first (if circuit not tripped)
        if (primaryAvailable()) {
            LLMResponse response = call.apply(primary, args);
            if (!"error".equals(response.finishReason())) {
                primaryFailures = 0;
                primaryTrippedAt = null;
                return response;
            }

            // Has streamed content already?
            if (hasStreamed != null && hasStreamed[0]) {
                boolean isTimeout = "timeout".equals(
                    response.errorKind() != null ? response.errorKind().toLowerCase() : "");
                if (isTimeout) {
                    log.warn("Primary model '{}' stream stalled after content; attempting failover",
                             primaryModel);
                    hasStreamed[0] = false;
                    if (onStreamRecover != null) onStreamRecover.run();
                } else {
                    log.warn("Primary model error but content already streamed; skipping failover");
                    return response;
                }
            }

            if (!shouldFallback(response)) {
                log.warn("Primary model '{}' returned non-fallbackable error: {}",
                         primaryModel, truncate(response.content(), 120));
                return response;
            }

            primaryFailures++;
            if (primaryFailures >= PRIMARY_FAILURE_THRESHOLD) {
                primaryTrippedAt = System.currentTimeMillis();
                log.warn("Primary model '{}' circuit open after {} consecutive failures",
                         primaryModel, primaryFailures);
            }
        } else {
            log.debug("Primary model '{}' circuit open; skipping", primaryModel);
        }

        // Try fallbacks
        LLMResponse lastResponse = null;
        boolean primarySkipped = !primaryAvailable();

        for (int i = 0; i < fallbackPresets.size(); i++) {
            ModelPreset fallback = fallbackPresets.get(i);
            String fallbackModel = fallback.model();

            // Check if previous response already streamed content
            if (hasStreamed != null && hasStreamed[0]) {
                boolean isTimeout = lastResponse != null
                    && "timeout".equals(
                        lastResponse.errorKind() != null
                            ? lastResponse.errorKind().toLowerCase() : "");
                if (isTimeout && onStreamRecover != null) {
                    hasStreamed[0] = false;
                    onStreamRecover.run();
                } else {
                    break;  // content already delivered, stop failover
                }
            }

            if (i == 0 && primarySkipped) {
                log.info("Primary '{}' circuit open, trying fallback '{}'",
                         primaryModel, fallbackModel);
            } else if (i == 0) {
                log.info("Primary '{}' failed, trying fallback '{}'",
                         primaryModel, fallbackModel);
            } else {
                log.info("Fallback '{}' also failed, trying '{}'",
                         fallbackPresets.get(i - 1).model(), fallbackModel);
            }

            try {
                LLMProvider fallbackProvider = providerFactory.apply(fallback);
                String origModel = args.model();
                int origMaxTokens = args.maxTokens();
                double origTemp = args.temperature();
                Object origToolChoice = args.toolChoice();

                // Override with fallback params
                // (In a real implementation, use reflection or a mutable builder)
                CallArgs fbArgs = new CallArgs(
                    args.messages(), args.tools(),
                    fallbackModel,
                    fallback.maxTokens(),
                    fallback.temperature(),
                    fallback.reasoningEffort(),
                    args.toolChoice()
                );

                LLMResponse fbResponse = call.apply(fallbackProvider, fbArgs);

                if (!"error".equals(fbResponse.finishReason())) {
                    log.info("Fallback '{}' succeeded after primary '{}' failed",
                             fallbackModel, primaryModel);
                    return fbResponse;
                }

                lastResponse = fbResponse;
                log.warn("Fallback '{}' also failed: {}",
                         fallbackModel, truncate(fbResponse.content(), 120));

            } catch (Exception exc) {
                log.warn("Failed to create provider for fallback '{}': {}",
                         fallbackModel, exc.getMessage());
            }
        }

        log.warn("All {} fallback model(s) failed", fallbackPresets.size());
        if (lastResponse != null) return lastResponse;
        return new LLMResponse(
            "Primary model '" + primaryModel + "' circuit open and no fallbacks available",
            "error");
    }

    /** Determine if this error is eligible for fallback. */
    static boolean shouldFallback(LLMResponse response) {
        if (Boolean.FALSE.equals(response.errorShouldRetry())) return false;

        Integer status = response.errorStatusCode();
        if (status != null && Set.of(400, 401, 403, 404, 422).contains(status)) return false;

        String kind = (response.errorKind() != null)
            ? response.errorKind().toLowerCase() : "";
        if (NON_FALLBACK_ERROR_KINDS.contains(kind)) return false;

        String errorType = (response.errorType() != null)
            ? response.errorType().toLowerCase() : "";
        String code = (response.errorCode() != null)
            ? response.errorCode().toLowerCase() : "";

        if (NON_FALLBACK_ERROR_KINDS.stream().anyMatch(
            t -> kind.contains(t) || errorType.contains(t) || code.contains(t))) {
            return false;
        }

        if (Boolean.TRUE.equals(response.errorShouldRetry())) return true;
        if (status != null && (Set.of(408, 409, 429).contains(status)
                               || (status >= 500 && status <= 599))) return true;
        if (FALLBACK_ERROR_KINDS.contains(kind)) return true;

        return false;
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
```

### 4. 其他 Provider 实现（概要）

#### `BedrockProvider.java` — AWS Bedrock

```java
// Uses AWS SDK for Java v2 (software.amazon.awssdk:bedrockruntime)
// Key: uses BedrockRuntimeClient.converse() / converseStream()
// boto3 → AWS SDK v2 (already async with SdkAsyncHttpClient)
// Image conversion: data URL → base64 decode → ImageBlock
// ToolFormat: ToolSpecification.builder() with ToolInputSchema
// Reasoning: ReasoningContentBlock; adaptive thinking config
```

Maven 依赖:
```xml
<dependency>
    <groupId>software.amazon.awssdk</groupId>
    <artifactId>bedrockruntime</artifactId>
</dependency>
```

#### `AzureOpenAiProvider.java`

```java
// Uses OpenAiClient pointed at {endpoint}/openai/v1/
// Two auth modes: static API key or DefaultAzureCredential
// Uses azure-identity for AAD token acquisition (async)
// All requests go to client.responses().create()
```

Maven 依赖:
```xml
<dependency>
    <groupId>com.azure</groupId>
    <artifactId>azure-identity</artifactId>
</dependency>
```

#### `GitHubCopilotProvider.java` — extends `OpenAICompatProvider`

```java
// OAuth device flow → GitHub token → Copilot API token exchange
// Token storage: FileTokenStorage (local JSON file)
// Token refresh: _refreshClientApiKey() before each chat/chatStream
// Delegates to super (OpenAICompatProvider) for actual LLM calls
// Sets Editor-Version/Editor-Plugin-Version headers
```

#### `OpenAiCodexProvider.java`

```java
// Uses OAuth token from oauth-cli-kit
// Raw HTTP (java.net.http.HttpClient) to Codex Responses API
// SSE parsing: manual line-by-line parsing of "data: {...}" stream
// SSL fallback: verify=true → if fail → verify=false retry
// Prompt cache key: SHA-256 of first 2 messages
// Structured error: CodexHTTPError with status_code, retry_after, error_type
```

### 5. Streaming 架构

在 Java 端，流式处理有两种路径：

| Provider | Stream 方式 | Java SDK/库 |
|----------|-----------|------------|
| Anthropic | SSE → ContentBlockDeltaEvent | `anthropic-java` 同步流式 API |
| OpenAICompat | SSE → ChatCompletionChunk | `openai-java` `createStreaming().stream()` |
| Bedrock | ConverseStream → EventStream | AWS SDK v2 `converseStream()` |
| Codex | 原生 SSE text/event-stream | `java.net.http.HttpClient` 手动解析 |

**关键原则：所有流式迭代在虚拟线程中同步执行。** Java 的 `Iterator<T>` / `Iterable<T>` 与虚拟线程完美配合——`for (T item : iterable)` 在 `InputStream` 上阻塞时自动让出载体线程。

```java
// Pattern: synchronous iteration over streaming response on a virtual thread
// No reactive streams needed. Virtual threads make blocking I/O scalable.
for (ChatCompletionChunk chunk :
     client.chat().completions().createStreaming(params).stream()) {
    // Process chunk synchronously
    if (onContentDelta != null && chunk.choices().get(0).delta().content().isPresent()) {
        onContentDelta.accept(chunk.choices().get(0).delta().content().get());
    }
}
```

### 6. 连接管理

| Provider | Client 生命周期 | 连接池 |
|----------|---------------|--------|
| Anthropic | 按实例创建, 请求间复用 | OkHttp ConnectionPool (默认) |
| OpenAICompat | 懒初始化 (_ensureClient), 线程安全 | OkHttp, 本地端点 keepalive_expiry=0 |
| Bedrock | 按实例创建 (AWS SDK 内部管理) | AWS CRT 客户端 |
| Copilot | 继承 OpenAICompat 的客户端 | 同上, 每次请求前刷新 API key |
| Codex | 按请求创建 HttpClient | Java HttpClient, 请求后关闭 |

## 关键设计决策

### SDK 选择

| Python SDK | Java SDK | 备注 |
|-----------|----------|------|
| `anthropic` (AsyncAnthropic) | `com.anthropic:anthropic-java` (`0.43.0`) | 维护良好, OkHttp 后端 |
| `openai` (AsyncOpenAI) | `com.openai:openai-java` | 静态方法构建, OkHttp 后端 |
| `boto3` (bedrock-runtime) | `software.amazon.awssdk:bedrockruntime` | AWS SDK v2, 原生异步 |
| `httpx` (HTTP 客户端) | `java.net.http.HttpClient` (Java 11+) | 内置于 JDK, 无需额外依赖 |

### 虚拟线程优于反应式流

对于 LLM 流式响应，Spring WebFlux/Project Reactor 的 `Flux<T>` 是可选的。虚拟线程使得简单的 `Iterator`-based 迭代同样高效：

```java
// Virtual threads approach (chosen for simplicity)
Iterable<ChatCompletionChunk> chunks = client.chat().completions()
    .createStreaming(params).stream();
for (ChatCompletionChunk chunk : chunks) {
    onContentDelta.accept(chunk.choices().get(0).delta().content().orElse(""));
}

// Alternative: WebFlux (if Spring reactive stack is preferred)
// Flux<ChatCompletionChunk> flux = ...;
// flux.subscribe(chunk -> onContentDelta.accept(...));
```

选择同步迭代器 + 虚拟线程而非 WebFlux 的理由：
1. 重试逻辑（`runWithRetry`）用 `Thread.sleep()` 实现比操作符链式调用更简单
2. 错误处理用 `try/catch` 比反应式 `onErrorResume`/`retryWhen` 更清晰
3. 调试更容易——调用栈是线性的
4. 性能相当——虚拟线程在 I/O 阻塞时自动让出资源

### 错误分类保真度

所有错误分类逻辑（~200行 Python）逐行移植到 Java。这些是运行时兼容性的核心——错误的分类会破坏重试策略和故障转移行为。

### 延迟加载

OpenAI 客户端创在 Windows 上建可能耗时 ~700ms。Java 的懒初始化避免了在不需要该 provider 的路径上支付此成本。使用 `volatile` + `ReentrantLock` 双检锁模式保证线程安全。

## 验证标准

```bash
# 编译
mvn compile -pl nanobot-providers

# Anthropic provider 测试
mvn test -pl nanobot-providers -Dtest=AnthropicProviderTest

# OpenAI compat provider 测试
mvn test -pl nanobot-providers -Dtest=OpenAiCompatProviderTest

# 消息格式转换测试
mvn test -pl nanobot-providers -Dtest=MessageConversionTest

# Fallback provider 测试（含熔断器）
mvn test -pl nanobot-providers -Dtest=FallbackProviderTest

# 集成测试（需要 API key）
ANTHROPIC_API_KEY=sk-ant-... mvn test -pl nanobot-providers -Dtest=AnthropicProviderIT
OPENAI_API_KEY=sk-... mvn test -pl nanobot-providers -Dtest=OpenAiCompatProviderIT
```

## 代码量估算

| 文件 | 行数 | 对标 Python |
|------|------|-------------|
| AnthropicProvider.java | ~550 | anthropic_provider.py (693行) |
| OpenAiCompatProvider.java | ~750 | openai_compat_provider.py (1,482行) |
| FallbackProvider.java | ~200 | fallback_provider.py (300行) |
| BedrockProvider.java | ~500 | bedrock_provider.py (754行) |
| AzureOpenAiProvider.java | ~150 | azure_openai_provider.py (253行) |
| GitHubCopilotProvider.java | ~200 | github_copilot_provider.py (262行) |
| OpenAiCodexProvider.java | ~250 | openai_codex_provider.py (323行) |
| Provider auto-configuration | ~80 | Spring `@Configuration` |
| **合计** | **~2,680** | |
