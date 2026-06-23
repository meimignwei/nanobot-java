package com.nanobot.agent.tools.impl;

import com.nanobot.agent.tools.*;
import com.nanobot.agent.tools.schema.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 多提供商网页搜索工具，支持 DuckDuckGo、Brave、Tavily、SearXNG、
 * Jina、Kagi、Exa、Olostep、Bocha、Volcengine。
 *
 * <p>对标 Python {@code nanobot/agent/tools/web.py WebSearchTool}（第 207-783 行）。
 */
public class WebSearchTool extends Tool {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_7_2) AppleWebKit/537.36";

    /** 工具参数 JSON Schema。 */
    // 对标 Python WebSearchTool.parameters
    private static final Map<String, Object> PARAMETERS =
            ToolParametersSchema.create(
                    List.of("query"),
                    null,
                    Map.of(
                            "query", new StringSchema("Search query"),
                            "count", new IntegerSchema(1,
                                    "Results (1-10)", 1, 10, null, true),
                            "timeRange", new StringSchema(
                                    "Optional time filter: OneDay, OneWeek, "
                                            + "OneMonth, OneYear, or "
                                            + "YYYY-MM-DD..YYYY-MM-DD",
                                    null, null, null, true),
                            "authLevel", new IntegerSchema(0,
                                    "Optional authority filter: 0=all, "
                                            + "1=authoritative",
                                    0, 1, null, true),
                            "queryRewrite", new BooleanSchema(
                                    "Optional provider-side query rewrite "
                                            + "for conversational searches",
                                    null, true)
                    )
            );

    private final String configProvider;
    private final String apiKey;
    private final String proxy;
    private final HttpClient httpClient;

    public WebSearchTool(String provider, String apiKey, String proxy) {
        this.configProvider = (provider != null && !provider.isEmpty())
                ? provider.strip().toLowerCase() : "duckduckgo";
        this.apiKey = apiKey;
        this.proxy = proxy;
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        if (proxy != null && !proxy.isEmpty()) {
            builder.proxy(java.net.ProxySelector.of(
                    new java.net.InetSocketAddress(proxy, 0)));
        }
        this.httpClient = builder.build();
    }

    public WebSearchTool() { this("duckduckgo", null, null); }

    @Override
    public String getName() { return "web_search"; }

    @Override
    public String getDescription() {
        return "Search the web. Returns titles, URLs, and snippets. "
                + "count defaults to 5 (max 10). "
                + "Some providers support timeRange, authLevel, and queryRewrite. "
                + "Use web_fetch to read a specific page in full.";
    }

    @Override
    public Map<String, Object> getParameters() {
        return ToolParameters.deepCopy(PARAMETERS);
    }

    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public boolean isExclusive() {
        return "duckduckgo".equals(effectiveProvider());
    }

    @Override
    public Set<String> getScopes() { return Set.of("core", "subagent"); }

    /**
     * 执行网页搜索，按提供商分发。
     *
     * @param params 已校验的工具参数
     * @return 搜索结果字符串的 CompletableFuture
     */
    @Override
    // 对标 Python WebSearchTool.execute()
    public CompletableFuture<Object> execute(Map<String, Object> params) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String query = paramString(params, "query");
                if (query == null || query.isBlank()) {
                    return "Error: query is required";
                }
                int count = Math.min(Math.max(
                        paramInt(params, "count", 5), 1), 10);
                String provider = effectiveProvider();
                String timeRange = paramString(params, "timeRange");
                int authLevel = paramInt(params, "authLevel", 0);
                boolean queryRewrite = Boolean.TRUE.equals(
                        params.get("queryRewrite"));

                return switch (provider) {
                    case "brave" -> searchBrave(query, count);
                    case "tavily" -> searchTavily(query, count);
                    case "searxng" -> searchSearXNG(query, count);
                    case "jina" -> searchJina(query, count);
                    case "kagi" -> searchKagi(query, count);
                    case "exa" -> searchExa(query, count);
                    case "olostep" -> searchOlostep(query, count);
                    case "bocha" -> searchBocha(query, count);
                    case "volcengine" -> searchVolcengine(query, count,
                            timeRange, authLevel, queryRewrite);
                    default -> searchDuckDuckGo(query, count);
                };
            } catch (Exception e) {
                return "Error searching web: " + e.getMessage();
            }
        });
    }

    /**
     * 解析实际使用的提供商，API key 缺失时回退到 DDG。
     *
     * @return 提供商名称
     */
    // 对标 Python WebSearchTool._effective_provider()
    private String effectiveProvider() {
        String p = configProvider;
        if ("duckduckgo".equals(p)) return "duckduckgo";
        if ("brave".equals(p)) return hasKey("BRAVE_API_KEY") ? "brave" : "duckduckgo";
        if ("tavily".equals(p)) return hasKey("TAVILY_API_KEY") ? "tavily" : "duckduckgo";
        if ("searxng".equals(p)) return hasKey("SEARXNG_BASE_URL") ? "searxng" : "duckduckgo";
        if ("jina".equals(p)) return hasKey("JINA_API_KEY") ? "jina" : "duckduckgo";
        if ("kagi".equals(p)) return hasKey("KAGI_API_KEY") ? "kagi" : "duckduckgo";
        if ("exa".equals(p)) return hasKey("EXA_API_KEY") ? "exa" : "duckduckgo";
        if ("olostep".equals(p)) return hasKey("OLOSTEP_API_KEY") ? "olostep" : "duckduckgo";
        if ("bocha".equals(p)) return hasKey("BOCHA_API_KEY") ? "bocha" : "duckduckgo";
        if ("volcengine".equals(p)) {
            return (hasKey("VOLCENGINE_SEARCH_API_KEY")
                    || hasKey("WEB_SEARCH_API_KEY")) ? "volcengine" : "duckduckgo";
        }
        return "duckduckgo";
    }

    private boolean hasKey(String envVar) {
        String key = apiKey != null ? apiKey : System.getenv(envVar);
        return key != null && !key.isEmpty();
    }

    // ==================== Provider Implementations ====================

    /**
     * DuckDuckGo 搜索（使用 Instant Answer API）。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_duckduckgo()
    private String searchDuckDuckGo(String query, int count) {
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.duckduckgo.com/?q="
                            + encoded + "&format=json&no_html=1&skip_disambig=1"))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(12))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: DuckDuckGo search HTTP " + response.statusCode();
            }

            String body = response.body();
            List<Map<String, String>> items = new ArrayList<>();

            String abstractText = extractJsonField(body, "AbstractText");
            String abstractUrl = extractJsonField(body, "AbstractURL");
            if (!abstractText.isEmpty()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("title", extractJsonField(body, "AbstractSource"));
                item.put("url", abstractUrl);
                item.put("content", abstractText);
                items.add(item);
            }
            items.addAll(parseDuckDuckGoTopics(body));
            items.addAll(parseDuckDuckGoResults(body));

            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: DuckDuckGo search failed: " + e.getMessage();
        }
    }

    /**
     * Brave Search 实现（含 429 单次重试）。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_brave()
    private String searchBrave(String query, int count) {
        String key = apiKey != null ? apiKey : System.getenv("BRAVE_API_KEY");
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.search.brave.com/res/v1/"
                            + "web/search?q=" + encoded + "&count=" + count))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", key)
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }
            if (response.statusCode() == 429) {
                return "Error: Brave search rate limited after retry. "
                        + "Retry later or reduce consecutive web_search calls.";
            }
            if (response.statusCode() >= 400) {
                return "Error: Brave search HTTP " + response.statusCode();
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(response.body());

            List<Map<String, String>> items = new ArrayList<>();
            var results = root.at("/web/results");
            if (results.isArray()) {
                for (var x : results) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", x.path("title").asText(""));
                    item.put("url", x.path("url").asText(""));
                    item.put("content", x.path("description").asText(""));
                    items.add(item);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: Brave search failed: " + e.getMessage();
        }
    }

    /**
     * Tavily Search 实现。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_tavily()
    private String searchTavily(String query, int count) {
        String key = apiKey != null ? apiKey : System.getenv("TAVILY_API_KEY");
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String body = mapper.writeValueAsString(Map.of(
                    "query", query, "max_results", count));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.tavily.com/search"))
                    .header("Authorization", "Bearer " + key)
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: Tavily search HTTP " + response.statusCode();
            }

            var root = mapper.readTree(response.body());
            List<Map<String, String>> items = new ArrayList<>();
            var results = root.get("results");
            if (results != null && results.isArray()) {
                for (var x : results) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", x.path("title").asText(""));
                    item.put("url", x.path("url").asText(""));
                    item.put("content", x.path("content").asText(""));
                    items.add(item);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * SearXNG 搜索实现。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_searxng()
    private String searchSearXNG(String query, int count) {
        String baseUrl = (System.getenv("SEARXNG_BASE_URL") != null)
                ? System.getenv("SEARXNG_BASE_URL").strip() : "";
        if (baseUrl.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        String endpoint = baseUrl.replaceAll("/+$", "") + "/search";
        if (!WebFetchTool.isValidUrl(endpoint)) {
            return "Error: invalid SearXNG URL: " + endpoint;
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "?q=" + encoded + "&format=json"))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: SearXNG search HTTP " + response.statusCode();
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(response.body());
            List<Map<String, String>> items = new ArrayList<>();
            var results = root.get("results");
            if (results != null && results.isArray()) {
                for (var x : results) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", x.path("title").asText(""));
                    item.put("url", x.path("url").asText(""));
                    item.put("content", x.path("content").asText(
                            x.path("snippet").asText("")));
                    items.add(item);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Jina Search 实现。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_jina()
    private String searchJina(String query, int count) {
        String key = apiKey != null ? apiKey : System.getenv("JINA_API_KEY");
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://s.jina.ai/" + encoded))
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer " + key)
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return searchDuckDuckGo(query, count);
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(response.body());
            var data = root.get("data");
            List<Map<String, String>> items = new ArrayList<>();
            if (data != null && data.isArray()) {
                int i = 0;
                for (var d : data) {
                    if (i++ >= count) break;
                    String content = d.path("content").asText("");
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", d.path("title").asText(""));
                    item.put("url", d.path("url").asText(""));
                    item.put("content", content.length() > 500
                            ? content.substring(0, 500) : content);
                    items.add(item);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return searchDuckDuckGo(query, count);
        }
    }

    /**
     * Kagi Search 实现。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_kagi()
    private String searchKagi(String query, int count) {
        String key = apiKey != null ? apiKey : System.getenv("KAGI_API_KEY");
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String body = mapper.writeValueAsString(Map.of(
                    "query", query, "limit", count));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://kagi.com/api/v1/search"))
                    .header("Authorization", "Bearer " + key)
                    .header("User-Agent", USER_AGENT)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: " + response.statusCode();
            }

            var root = mapper.readTree(response.body());
            List<Map<String, String>> items = new ArrayList<>();
            var searchNode = root.at("/data/search");
            if (searchNode.isArray()) {
                for (var d : searchNode) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", d.path("title").asText(""));
                    item.put("url", d.path("url").asText(""));
                    item.put("content", d.path("snippet").asText(""));
                    items.add(item);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * Exa Search 实现。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_exa()
    private String searchExa(String query, int count) {
        String key = apiKey != null ? apiKey : System.getenv("EXA_API_KEY");
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String body = mapper.writeValueAsString(Map.of(
                    "query", query,
                    "numResults", count,
                    "contents", Map.of("highlights", true)));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.exa.ai/search"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", key)
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                return "Error: Exa search rate limited. "
                        + "Try again later or reduce search frequency.";
            }
            if (response.statusCode() >= 400) {
                return "Error: Exa search failed ("
                        + response.statusCode() + ")";
            }

            var root = mapper.readTree(response.body());
            List<Map<String, String>> items = new ArrayList<>();
            var results = root.get("results");
            if (results != null && results.isArray()) {
                for (var result : results) {
                    StringBuilder content = new StringBuilder();
                    var highlights = result.get("highlights");
                    if (highlights != null && highlights.isArray()) {
                        for (var h : highlights) {
                            if (!h.asText("").isEmpty()) {
                                if (content.length() > 0) content.append('\n');
                                content.append(h.asText());
                            }
                        }
                    }
                    if (content.isEmpty()) {
                        String s = result.path("summary").asText(
                                result.path("text").asText(""));
                        content.append(s.length() > 500
                                ? s.substring(0, 500) : s);
                    }
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", result.path("title").asText(""));
                    item.put("url", result.path("url").asText(""));
                    item.put("content", content.toString());
                    items.add(item);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: Exa search failed: " + e.getMessage();
        }
    }

    /**
     * Olostep Search 实现（HTTP API 回退，对标 Python _search_olostep()）。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_olostep()
    private String searchOlostep(String query, int count) {
        String key = apiKey != null ? apiKey : System.getenv("OLOSTEP_API_KEY");
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String body = mapper.writeValueAsString(Map.of("task", query));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.olostep.com/v1/answers"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Olostep search error: HTTP " + response.statusCode();
            }

            var root = mapper.readTree(response.body());
            StringBuilder sources = new StringBuilder();
            var srcArray = root.path("sources");
            if (srcArray.isArray()) {
                int i = 1;
                for (var src : srcArray) {
                    if (i > count) break;
                    String title = src.path("title").asText("");
                    String url = src.path("url").asText("");
                    if (!title.isEmpty() && !url.isEmpty()) {
                        sources.append(i).append(". ").append(title)
                                .append(" — ").append(url).append('\n');
                    } else if (!url.isEmpty()) {
                        sources.append(i).append(". ").append(url).append('\n');
                    } else if (!title.isEmpty()) {
                        sources.append(i).append(". ").append(title).append('\n');
                    }
                    i++;
                }
            }
            String answer = root.path("answer").asText("");
            List<Map<String, String>> items = new ArrayList<>();
            Map<String, String> item = new LinkedHashMap<>();
            item.put("title", !answer.isEmpty() ? answer : "Olostep answer");
            item.put("url", "");
            item.put("content", sources.toString());
            items.add(item);
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Olostep search error: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * Bocha Search 实现。
     *
     * @param query 搜索查询
     * @param count 结果数量
     * @return 格式化结果字符串
     */
    // 对标 Python _search_bocha()
    private String searchBocha(String query, int count) {
        String key = apiKey != null ? apiKey : System.getenv("BOCHA_API_KEY");
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String body = mapper.writeValueAsString(Map.of(
                    "query", query,
                    "freshness", "noLimit",
                    "summary", true,
                    "count", count));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.bochaai.com/v1/web-search"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                return "Error: Bocha search rate-limited (HTTP 429). "
                        + "Wait and retry.";
            }
            if (response.statusCode() >= 400) {
                return "Error: Bocha search HTTP " + response.statusCode();
            }

            var root = mapper.readTree(response.body());
            var wrappedData = root.path("data");
            var resultData = wrappedData.isMissingNode() ? root : wrappedData;
            List<Map<String, String>> items = new ArrayList<>();
            var webPages = resultData.at("/webPages/value");
            if (webPages.isArray()) {
                for (var x : webPages) {
                    Map<String, String> item = new LinkedHashMap<>();
                    item.put("title", x.path("name").asText(""));
                    item.put("url", x.path("url").asText(""));
                    item.put("content", !x.path("summary").asText("").isEmpty()
                            ? x.path("summary").asText("")
                            : x.path("snippet").asText(""));
                    items.add(item);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: Bocha search failed: " + e.getMessage();
        }
    }

    /**
     * Volcengine Search 实现（含 timeRange/authLevel/queryRewrite 参数处理）。
     *
     * @param query        搜索查询
     * @param count        结果数量
     * @param timeRange    时间范围过滤器
     * @param authLevel    权威级别过滤器
     * @param queryRewrite 查询改写开关
     * @return 格式化结果字符串
     */
    // 对标 Python _search_volcengine()
    private String searchVolcengine(String query, int count,
                                     String timeRange, int authLevel,
                                     boolean queryRewrite) {
        String key = apiKey != null ? apiKey
                : System.getenv("VOLCENGINE_SEARCH_API_KEY");
        if (key == null || key.isEmpty()) {
            key = System.getenv("WEB_SEARCH_API_KEY");
        }
        if (key == null || key.isEmpty()) {
            return searchDuckDuckGo(query, count);
        }

        // 校验 timeRange
        Set<String> validTimeRanges = Set.of(
                "OneDay", "OneWeek", "OneMonth", "OneYear");
        String dateRangeRe = "^\\d{4}-\\d{2}-\\d{2}\\.\\.\\d{4}-\\d{2}-\\d{2}$";
        if (timeRange != null && !timeRange.isEmpty()
                && !validTimeRanges.contains(timeRange)
                && !timeRange.matches(dateRangeRe)) {
            return "Error: timeRange must be OneDay, OneWeek, OneMonth, "
                    + "OneYear, or YYYY-MM-DD..YYYY-MM-DD";
        }
        if (authLevel != 0 && authLevel != 1) {
            return "Error: authLevel must be 0 or 1";
        }

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("Query", query);
            body.put("SearchType", "web");
            body.put("Count", count);
            body.put("NeedSummary", true);
            if (timeRange != null && !timeRange.isEmpty()) {
                body.put("TimeRange", timeRange);
            }
            if (authLevel > 0) {
                body.put("Filter",
                        Map.of("AuthInfoLevel", authLevel));
            }
            if (queryRewrite) {
                body.put("QueryControl",
                        Map.of("QueryRewrite", true));
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "https://open.feedcoopapi.com/search_api/web_search"))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("X-Traffic-Tag", "nanobot")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 429) {
                return "Error: Volcengine search rate limited. "
                        + "Try again later or reduce search frequency.";
            }
            if (response.statusCode() >= 400) {
                return "Error: Volcengine search failed ("
                        + response.statusCode() + ")";
            }

            var root = mapper.readTree(response.body());

            // 检查错误
            var error = root.path("ResponseMetadata").path("Error");
            if (!error.isMissingNode()) {
                String code = error.path("Code").asText("unknown");
                String message = error.path("Message").asText("");
                return "Error: Volcengine search error " + code
                        + ": " + message;
            }

            var result = root.path("Result");
            if (result.isMissingNode()) result = root;
            var webResults = result.path("WebResults");
            if (webResults.isMissingNode()) {
                webResults = result.path("webResults");
            }
            if (webResults.isMissingNode()) {
                webResults = result.path("results");
            }

            List<Map<String, String>> items = new ArrayList<>();
            if (webResults.isArray()) {
                for (var item : webResults) {
                    String title = firstNonEmpty(item,
                            "Title", "title", "");
                    String url = firstNonEmpty(item,
                            "Url", "URL", "url", "");
                    String siteName = firstNonEmpty(item,
                            "SiteName", "siteName", "Site", "");
                    String authDes = firstNonEmpty(item,
                            "AuthInfoDes", "authInfoDes", "");
                    String publish = firstNonEmpty(item,
                            "PublishTime", "publishTime", "");
                    String summary = firstNonEmpty(item,
                            "Summary", "summary", "Snippet",
                            "snippet", "Content", "content", "");

                    StringBuilder metaStr = new StringBuilder();
                    if (!siteName.isEmpty()) metaStr.append(siteName);
                    if (!authDes.isEmpty()) {
                        if (metaStr.length() > 0) metaStr.append(" | ");
                        metaStr.append(authDes);
                    }
                    if (!publish.isEmpty()) {
                        if (metaStr.length() > 0) metaStr.append(" | ");
                        metaStr.append(publish);
                    }

                    StringBuilder content = new StringBuilder();
                    if (metaStr.length() > 0) content.append(metaStr);
                    if (!summary.isEmpty()) {
                        if (content.length() > 0) content.append('\n');
                        content.append(summary);
                    }

                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("title", title);
                    m.put("url", url);
                    m.put("content", content.toString());
                    items.add(m);
                }
            }
            return formatResults(query, items, count);
        } catch (Exception e) {
            return "Error: Volcengine search failed: " + e.getMessage();
        }
    }

    /** 从 JsonNode 中获取第一个非空字段值。 */
    private static String firstNonEmpty(
            com.fasterxml.jackson.databind.JsonNode node, String... fields) {
        for (String f : fields) {
            String val = node.path(f).asText("");
            if (!val.isEmpty()) return val;
        }
        return "";
    }

    // ==================== JSON 字段提取（DDG 手动解析） ====================

    /** 从 JSON 中手动提取顶层字符串字段值。 */
    private static String extractJsonField(String json, String key) {
        String pattern = "\"" + key + "\"\\s*:\\s*\"";
        int start = json.indexOf(pattern);
        if (start < 0) return "";
        start += pattern.length();
        StringBuilder val = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == start || json.charAt(i - 1) != '\\')) break;
            val.append(c);
        }
        return val.toString().replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /** 解析 DuckDuckGo RelatedTopics 数组。 */
    private static List<Map<String, String>> parseDuckDuckGoTopics(
            String body) {
        List<Map<String, String>> items = new ArrayList<>();
        int idx = 0;
        String textMarker = "\"Text\"\\s*:\\s*\"";
        String urlMarker = "\"FirstURL\"\\s*:\\s*\"";
        while (true) {
            int textStart = indexOf(body, textMarker, idx);
            if (textStart < 0) break;
            textStart += textMarker.length() - 2;
            int textEnd = body.indexOf('"', textStart + 1);
            if (textEnd < 0) break;
            String text = body.substring(textStart + 1, textEnd);

            int urlStart = indexOf(body, urlMarker, textEnd);
            String url = "";
            if (urlStart >= 0 && urlStart < textEnd + 500) {
                urlStart += urlMarker.length() - 2;
                int urlEnd = body.indexOf('"', urlStart + 1);
                if (urlEnd >= 0) {
                    url = body.substring(urlStart + 1, urlEnd);
                }
            }
            if (!text.isEmpty()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("title", text);
                item.put("url", url);
                item.put("content", "");
                items.add(item);
            }
            idx = textEnd + 1;
        }
        return items;
    }

    /** 简单正则索引查找。 */
    private static int indexOf(String s, String regex, int from) {
        var m = java.util.regex.Pattern.compile(regex)
                .matcher(s).region(from, s.length());
        return m.find() ? m.start() : -1;
    }

    /** 解析 DuckDuckGo Results 数组。 */
    private static List<Map<String, String>> parseDuckDuckGoResults(
            String body) {
        List<Map<String, String>> items = new ArrayList<>();
        int idx = 0;
        while ((idx = body.indexOf("\"FirstURL\"", idx)) >= 0) {
            int urlStart = body.indexOf('"', idx + 11);
            if (urlStart < 0) break;
            urlStart++;
            int urlEnd = body.indexOf('"', urlStart);
            if (urlEnd < 0) break;
            String url = body.substring(urlStart, urlEnd);

            int textStart = body.indexOf("\"Text\"", urlEnd);
            if (textStart < 0 || textStart > urlEnd + 500) break;
            textStart = body.indexOf('"', textStart + 6);
            if (textStart < 0) break;
            textStart++;
            int textEnd = body.indexOf('"', textStart);
            if (textEnd < 0) break;
            String text = body.substring(textStart, textEnd);

            if (!text.isEmpty()) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("title", text);
                item.put("url", url);
                item.put("content", "");
                items.add(item);
            }
            idx = textEnd + 1;
        }
        return items;
    }

    // ==================== 结果格式化 ====================

    /**
     * 格式化搜索结果为共享纯文本输出。
     *
     * @param query 搜索查询
     * @param items 结果列表
     * @param n     最大返回数
     * @return 格式化字符串
     */
    // 对标 Python _format_results()
    static String formatResults(String query, List<Map<String, String>> items,
                                 int n) {
        if (items.isEmpty()) return "No results for: " + query;
        StringBuilder sb = new StringBuilder("Results for: ").append(query)
                .append('\n').append('\n');
        int count = 0;
        for (Map<String, String> item : items) {
            if (count >= n) break;
            count++;
            String title = normalize(stripTags(
                    item.getOrDefault("title", "")));
            String snippet = normalize(stripTags(
                    item.getOrDefault("content", "")));
            sb.append(count).append(". ").append(title).append('\n');
            sb.append("   ").append(item.getOrDefault("url", "")).append('\n');
            if (!snippet.isEmpty()) {
                sb.append("   ").append(snippet).append('\n');
            }
        }
        if (count == 0) sb.append("No results found.");
        return sb.toString();
    }

    /**
     * 去除 HTML 标签并解码实体。
     *
     * @param text 带 HTML 标签的文本
     * @return 清理后的文本
     */
    // 对标 Python _strip_tags()
    static String stripTags(String text) {
        if (text == null || text.isEmpty()) return "";
        text = text.replaceAll("(?i)<script[\\s\\S]*?</script>", "");
        text = text.replaceAll("(?i)<style[\\s\\S]*?</style>", "");
        text = text.replaceAll("<[^>]+>", "");
        text = text.replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'")
                .replace("&#x27;", "'").replace("&#x2F;", "/");
        return text.strip();
    }

    /**
     * 规范化空白字符。
     *
     * @param text 待规范化的文本
     * @return 规范化后的文本
     */
    // 对标 Python _normalize()
    static String normalize(String text) {
        if (text == null || text.isEmpty()) return "";
        text = text.replaceAll("[ \\t]+", " ");
        text = text.replaceAll("\\n{3,}", "\n\n");
        return text.strip();
    }

    // ==================== 参数辅助 ====================

    private static String paramString(Map<String, Object> params, String key) {
        Object val = params.get(key);
        return val instanceof String s ? s : null;
    }

    private static int paramInt(Map<String, Object> params, String key, int def) {
        Object val = params.get(key);
        if (val instanceof Integer i) return i;
        if (val instanceof Number n) return n.intValue();
        return def;
    }
}
