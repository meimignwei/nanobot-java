package com.nanobot.providers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nanobot.providers.base.LLMResponse;
import com.nanobot.providers.base.ThrowingConsumer;
import jakarta.annotation.Nullable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * GitHub Copilot OAuth-backed provider.
 * Port of Python GitHubCopilotProvider (github_copilot_provider.py, 261 lines).
 *
 * Subclasses {@link OpenAiCompatProvider}, exchanges a stored GitHub OAuth token
 * for Copilot API access tokens, and delegates to super.chat/chatStream.
 */
public class GitHubCopilotProvider extends OpenAiCompatProvider {

    static final String DEFAULT_COPILOT_TOKEN_URL = "https://api.github.com/copilot_internal/v2/token";
    static final String EDITOR_VERSION = "vscode/1.99.0";
    static final String EDITOR_PLUGIN_VERSION = "copilot-chat/0.26.0";
    static final String USER_AGENT = "nanobot/0.1";
    static final long EXPIRY_SKEW_SECONDS = 60;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private String copilotAccessToken;
    private long copilotExpiresAt; // epoch seconds

    public GitHubCopilotProvider(String defaultModel) {
        super(
                "no-key",                       // apiKey — refreshed dynamically
                "https://api.githubcopilot.com", // apiBase
                defaultModel != null ? defaultModel : "github-copilot/gpt-4.1",
                Map.of(                         // extraHeaders
                        "Editor-Version", EDITOR_VERSION,
                        "Editor-Plugin-Version", EDITOR_PLUGIN_VERSION,
                        "User-Agent", USER_AGENT
                ),
                ProviderRegistry.findByName("github_copilot").orElse(null),
                null,                           // extraBody
                "auto",                         // apiType
                null                            // extraQuery
        );
    }

    // ------------------------------------------------------------------
    // Token management
    // ------------------------------------------------------------------

    private synchronized String getCopilotAccessToken() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        if (copilotAccessToken != null && now < copilotExpiresAt - EXPIRY_SKEW_SECONDS) {
            return copilotAccessToken;
        }

        GitHubToken githubToken = loadGitHubToken();
        if (githubToken == null || githubToken.access == null || githubToken.access.isEmpty()) {
            throw new IllegalStateException(
                    "GitHub Copilot is not logged in. Run: nanobot provider login github-copilot");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(DEFAULT_COPILOT_TOKEN_URL))
                .header("Authorization", "token " + githubToken.access)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Editor-Version", EDITOR_VERSION)
                .header("Editor-Plugin-Version", EDITOR_PLUGIN_VERSION)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString());
        response.previousResponse(); // follow redirects
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "GitHub Copilot token exchange failed: HTTP " + response.statusCode());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new ObjectMapper().readValue(response.body(),
                new TypeReference<Map<String, Object>>() {});

        String token = (String) payload.get("token");
        if (token == null || token.isEmpty()) {
            throw new IllegalStateException("GitHub Copilot token exchange returned no token.");
        }

        Object expiresAt = payload.get("expires_at");
        if (expiresAt instanceof Number n) {
            copilotExpiresAt = n.longValue();
        } else {
            int refreshIn = payload.get("refresh_in") instanceof Number n
                    ? n.intValue() : 1500;
            copilotExpiresAt = now + refreshIn;
        }
        copilotAccessToken = token;
        return copilotAccessToken;
    }

    private String refreshClientApiKey() throws Exception {
        String token = getCopilotAccessToken();
        this.apiKey = token;
        return token;
    }

    // ------------------------------------------------------------------
    // chat / chatStream overrides
    // ------------------------------------------------------------------

    @Override
    public LLMResponse chat(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice
    ) throws Exception {
        refreshClientApiKey();
        return super.chat(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice);
    }

    @Override
    public LLMResponse chatStream(
            List<Map<String, Object>> messages,
            @Nullable List<Map<String, Object>> tools,
            @Nullable String model,
            int maxTokens,
            double temperature,
            @Nullable String reasoningEffort,
            @Nullable Object toolChoice,
            @Nullable ThrowingConsumer<String> onContentDelta,
            @Nullable ThrowingConsumer<String> onThinkingDelta,
            @Nullable ThrowingConsumer<Map<String, Object>> onToolCallDelta
    ) throws Exception {
        refreshClientApiKey();
        return super.chatStream(messages, tools, model, maxTokens, temperature,
                reasoningEffort, toolChoice, onContentDelta, onThinkingDelta, onToolCallDelta);
    }

    // ------------------------------------------------------------------
    // GitHub OAuth token storage (module-level utilities)
    // ------------------------------------------------------------------

    record GitHubToken(@Nullable String access, @Nullable String refresh,
                       long expires, @Nullable String accountId) {}

    static final String TOKEN_FILENAME = "github-copilot.json";
    static final String TOKEN_APP_NAME = "nanobot";

    static Path tokenFilePath() {
        String dataDir = System.getenv().getOrDefault("XDG_DATA_HOME",
                System.getProperty("user.home") + "/.local/share");
        return Paths.get(dataDir, TOKEN_APP_NAME, TOKEN_FILENAME);
    }

    @Nullable
    static GitHubToken loadGitHubToken() {
        try {
            Path path = tokenFilePath();
            if (!Files.exists(path)) return null;
            String json = Files.readString(path);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = new ObjectMapper().readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            @Nullable String access = (String) data.get("access");
            @Nullable String refresh = (String) data.get("refresh");
            long expires = data.get("expires") instanceof Number n ? n.longValue() : 0;
            @Nullable String accountId = (String) data.get("account_id");
            if (access == null || access.isEmpty()) return null;
            return new GitHubToken(access, refresh, expires, accountId);
        } catch (Exception e) {
            return null;
        }
    }

    static void saveGitHubToken(GitHubToken token) {
        try {
            Path path = tokenFilePath();
            Files.createDirectories(path.getParent());
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("access", token.access);
            data.put("refresh", token.refresh != null ? token.refresh : "");
            data.put("expires", token.expires);
            data.put("account_id", token.accountId);
            Files.writeString(path, new ObjectMapper().writeValueAsString(data));
        } catch (Exception e) {
            System.err.println("Failed to save GitHub Copilot token: " + e.getMessage());
        }
    }

    /** Return the persisted GitHub OAuth token if available (public for status checks). */
    @Nullable
    public static GitHubToken getLoginStatus() {
        return loadGitHubToken();
    }
}
