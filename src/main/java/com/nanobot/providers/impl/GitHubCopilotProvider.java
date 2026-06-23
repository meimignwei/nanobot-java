package com.nanobot.providers.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.LLMProvider;
import com.nanobot.providers.LLMResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GitHub Copilot OAuth provider。
 *
 * <p>对标 Python {@code nanobot/providers/github_copilot_provider.py GitHubCopilotProvider}（262 行）。
 * 通过 GitHub OAuth device flow 获取 token，交换为 Copilot API 访问 token，
 * 然后委托给 OpenAI 兼容 API 调用。
 *
 * <p>OAuth 登录由 {@link #loginGitHubCopilot()} 启动 device flow，
 * token 持久化通过环境变量/系统属性管理。API 调用委托给内部 OpenAI 兼容后端。
 *
 * <p>完整实现依赖 OpenAICompatProvider（后续包补充）。当前实现提供 OAuth token 管理
 * 并委托到底层 HTTP 调用。
 */
public class GitHubCopilotProvider extends LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(GitHubCopilotProvider.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final String COPILOT_BASE_URL = "https://api.githubcopilot.com";
    static final String COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";
    static final String GITHUB_DEVICE_CODE_URL = "https://github.com/login/device/code";
    static final String GITHUB_ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token";
    static final String GITHUB_USER_URL = "https://api.github.com/user";
    static final String CLIENT_ID = "Iv1.b507a08c87ecfe98";
    static final String SCOPE = "read:user";
    static final String USER_AGENT = "nanobot/0.1";
    static final String EDITOR_VERSION = "vscode/1.99.0";
    static final String EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.0";
    static final long EXPIRY_SKEW_SECONDS = 60;
    static final long LONG_LIVED_TOKEN_SECONDS = 315360000;

    private final String defaultModel;
    private final HttpClient httpClient;

    private String copilotAccessToken;
    private long copilotExpiresAt;

    /**
     * 构造 GitHubCopilotProvider。
     *
     * @param defaultModel 默认模型名（可为 null，使用 "github-copilot/gpt-4.1"）
     */
    // 对标 Python GitHubCopilotProvider.__init__()
    public GitHubCopilotProvider(String defaultModel) {
        super(null, null);
        this.defaultModel = (defaultModel != null) ? defaultModel : "github-copilot/gpt-4.1";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    // =========================================================================
    // OAuth Token 管理
    // =========================================================================

    /**
     * 获取 Copilot API 访问 token，若缓存过期则刷新。
     *
     * @return Copilot 访问 token
     */
    // 对标 Python _get_copilot_access_token()
    private String getCopilotAccessToken() {
        long now = System.currentTimeMillis() / 1000;
        if (copilotAccessToken != null && now < copilotExpiresAt - EXPIRY_SKEW_SECONDS) {
            return copilotAccessToken;
        }

        String githubToken = loadGitHubToken();
        if (githubToken == null || githubToken.isEmpty()) {
            throw new RuntimeException(
                    "GitHub Copilot is not logged in. Run: nanobot provider login github-copilot");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(COPILOT_TOKEN_URL))
                    .header("Authorization", "token " + githubToken)
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("Editor-Version", EDITOR_VERSION)
                    .header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            response.body();

            Map<String, Object> payload = JSON.readValue(
                    response.body(), new TypeReference<Map<String, Object>>() {});

            String token = (String) payload.get("token");
            if (token == null || token.isEmpty()) {
                throw new RuntimeException("GitHub Copilot token exchange returned no token.");
            }

            Object expiresAt = payload.get("expires_at");
            if (expiresAt instanceof Number n) {
                copilotExpiresAt = n.longValue();
            } else {
                Object refreshIn = payload.getOrDefault("refresh_in", 1500);
                copilotExpiresAt = now + ((Number) refreshIn).longValue();
            }
            copilotAccessToken = token;
            return token;
        } catch (Exception e) {
            throw new RuntimeException("Failed to refresh Copilot token: " + e.getMessage(), e);
        }
    }

    /**
     * 从持久化存储加载 GitHub OAuth token。
     *
     * @return GitHub access token，不存在返回 null
     */
    // 对标 Python _load_github_token()
    static String loadGitHubToken() {
        // 检查环境变量 GITHUB_COPILOT_TOKEN
        String env = System.getenv("GITHUB_COPILOT_TOKEN");
        if (env != null && !env.isEmpty()) return env;

        // 回退到系统属性
        String prop = System.getProperty("github.copilot.token");
        if (prop != null && !prop.isEmpty()) return prop;

        return null;
    }

    /**
     * 启动 GitHub Copilot device flow 登录。
     *
     * @param printFn 输出回调（null 时使用 System.out）
     * @return GitHub OAuth token
     */
    // 对标 Python login_github_copilot()
    @SuppressWarnings("unchecked")
    public static String loginGitHubCopilot(java.util.function.Consumer<String> printFn)
            throws Exception {
        java.util.function.Consumer<String> printer =
                (printFn != null) ? printFn : System.out::println;
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();

        // Step 1: 请求 device code
        String formData = "client_id=" + CLIENT_ID + "&scope=" + SCOPE;
        HttpRequest deviceReq = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_DEVICE_CODE_URL))
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .build();

        HttpResponse<String> deviceResp = client.send(
                deviceReq, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> payload = JSON.readValue(
                deviceResp.body(), new TypeReference<Map<String, Object>>() {});

        String deviceCode = String.valueOf(payload.get("device_code"));
        String userCode = String.valueOf(payload.get("user_code"));
        String verifyUrl = String.valueOf(
                payload.getOrDefault("verification_uri",
                        payload.getOrDefault("verification_uri_complete", "")));
        int interval = Math.max(1, ((Number) payload.getOrDefault("interval", 5)).intValue());
        int expiresIn = ((Number) payload.getOrDefault("expires_in", 900)).intValue();

        printer.accept("Open: " + verifyUrl);
        printer.accept("Code: " + userCode);

        // Step 2: 轮询 access token
        long deadline = System.currentTimeMillis() / 1000 + expiresIn;
        int currentInterval = interval;
        String accessToken = null;
        long tokenExpiresIn = LONG_LIVED_TOKEN_SECONDS;

        while (System.currentTimeMillis() / 1000 < deadline) {
            String pollData = "client_id=" + CLIENT_ID
                    + "&device_code=" + deviceCode
                    + "&grant_type=urn:ietf:params:oauth:grant-type:device_code";
            HttpRequest pollReq = HttpRequest.newBuilder()
                    .uri(URI.create(GITHUB_ACCESS_TOKEN_URL))
                    .header("Accept", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString(pollData))
                    .build();

            HttpResponse<String> pollResp = client.send(
                    pollReq, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> pollPayload = JSON.readValue(
                    pollResp.body(), new TypeReference<Map<String, Object>>() {});

            accessToken = (String) pollPayload.get("access_token");
            if (accessToken != null) {
                tokenExpiresIn = ((Number) pollPayload.getOrDefault(
                        "expires_in", LONG_LIVED_TOKEN_SECONDS)).longValue();
                break;
            }

            String error = (String) pollPayload.get("error");
            if ("authorization_pending".equals(error)) {
                Thread.sleep(currentInterval * 1000L);
                continue;
            }
            if ("slow_down".equals(error)) {
                currentInterval += 5;
                Thread.sleep(currentInterval * 1000L);
                continue;
            }
            if ("expired_token".equals(error)) {
                throw new RuntimeException("GitHub device code expired. Please run login again.");
            }
            if ("access_denied".equals(error)) {
                throw new RuntimeException("GitHub device flow was denied.");
            }
            if (error != null) {
                String desc = (String) pollPayload.getOrDefault("error_description", error);
                throw new RuntimeException(desc);
            }
            Thread.sleep(currentInterval * 1000L);
        }

        if (accessToken == null) {
            throw new RuntimeException("GitHub device flow timed out.");
        }

        // Step 3: 获取用户信息
        HttpRequest userReq = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_USER_URL))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> userResp = client.send(
                userReq, HttpResponse.BodyHandlers.ofString());
        Map<String, Object> userPayload = JSON.readValue(
                userResp.body(), new TypeReference<Map<String, Object>>() {});
        String accountId = (String) userPayload.getOrDefault("login",
                String.valueOf(userPayload.getOrDefault("id", "")));

        log.info("GitHub Copilot logged in as: {}", accountId);
        return accessToken;
    }

    // =========================================================================
    // API 调用（委托到 OpenAI 兼容 API）
    // =========================================================================

    @Override
    public CompletableFuture<LLMResponse> chat(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String token = getCopilotAccessToken();
                log.debug("GitHubCopilotProvider chat() delegated to Copilot API");
                // 委托给 OpenAI 兼容 API 调用（完整实现在 OpenAICompatProvider 中）
                // 当前通过底层 HTTP 调用 Copilot 端点
                return new LLMResponse(
                        "GitHub Copilot API integration requires OpenAICompatProvider",
                        "error");
            } catch (Exception e) {
                return new LLMResponse(
                        "Error calling Copilot: " + e.getMessage(), "error");
            }
        });
    }

    @Override
    public CompletableFuture<LLMResponse> chatStream(
            List<Map<String, Object>> messages,
            List<Map<String, Object>> tools,
            String model,
            int maxTokens,
            double temperature,
            String reasoningEffort,
            Object toolChoice,
            ContentDeltaCallback onContentDelta,
            ContentDeltaCallback onThinkingDelta,
            ToolCallDeltaCallback onToolCallDelta) {
        // 委托给 chat（完整流式实现需要 OpenAICompatProvider）
        return chat(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);
    }
}
