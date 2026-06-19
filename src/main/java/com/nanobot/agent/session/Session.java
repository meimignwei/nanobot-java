package com.nanobot.agent.session;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class Session {

    private static final Pattern MESSAGE_TIME_PREFIX = Pattern.compile("^\\[Message Time: [^\\]]+\\]\n?");
    private static final Pattern LOCAL_IMAGE_BREADCRUMB = Pattern.compile("^\\[image: (?:/|~)[^\\]]+\\]\\s*$");
    private static final Pattern TOOL_CALL_ECHO = Pattern.compile("^\\s*(?:generate_image|message)\\([^)]*\\)\\s*$");

    private final String key;
    private final List<Map<String, Object>> messages;
    private final Map<String, Object> metadata;
    private final Instant createdAt;
    private Instant updatedAt;
    private int lastConsolidated;

    public Session(String key) {
        this.key = key;
        this.messages = new ArrayList<>();
        this.metadata = new LinkedHashMap<>();
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.lastConsolidated = 0;
    }

    // -- accessors --

    public String key() { return key; }
    public List<Map<String, Object>> messages() { return messages; }
    public Map<String, Object> metadata() { return metadata; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public int lastConsolidated() { return lastConsolidated; }

    public void setLastConsolidated(int value) {
        if (value < 0 || value > messages.size()) {
            this.lastConsolidated = 0;
        } else {
            this.lastConsolidated = value;
        }
    }

    // -- addMessage --

    public void addMessage(String role, String content) {
        addMessage(role, content, null);
    }

    public void addMessage(String role, String content, Map<String, Object> extra) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", role);
        msg.put("content", content);
        msg.put("timestamp", Instant.now().toString());
        if (extra != null) {
            msg.putAll(extra);
        }
        messages.add(msg);
        updatedAt = Instant.now();
    }

    // -- getHistory --

    public List<Map<String, Object>> getHistory(int maxMessages, int maxTokens, boolean includeTimestamps) {
        int effectiveLimit = maxMessages > 0 ? maxMessages : 120;
        var unconsolidated = messages.subList(lastConsolidated, messages.size());
        int totalUnconsolidated = unconsolidated.size();
        int sliceStart = Math.max(0, totalUnconsolidated - effectiveLimit);
        var sliced = new ArrayList<>(unconsolidated.subList(sliceStart, totalUnconsolidated));

        // Try to start at a user turn, allowing preceding _channel_delivery
        for (int i = 0; i < sliced.size(); i++) {
            if ("user".equals(sliced.get(i).get("role"))) {
                if (i > 0 && sliced.get(i - 1).get("_channel_delivery") != null) {
                    sliced = new ArrayList<>(sliced.subList(i - 1, sliced.size()));
                } else {
                    sliced = new ArrayList<>(sliced.subList(i, sliced.size()));
                }
                break;
            }
        }

        // Drop orphan tool results at the front
        int start = findLegalMessageStart(sliced);
        if (start > 0) {
            sliced = new ArrayList<>(sliced.subList(start, sliced.size()));
        }

        // Build output
        var out = new ArrayList<Map<String, Object>>();
        for (var msg : sliced) {
            if (msg.get("_command") != null) {
                continue;
            }
            var content = msg.get("content");
            var role = msg.get("role");

            // Sanitize assistant replay text
            if ("assistant".equals(role) && content instanceof String s) {
                content = sanitizeAssistantReplayText(s);
            }

            // Synthesize image breadcrumbs
            var media = msg.get("media");
            if ("user".equals(role) && media instanceof List<?> ml && !ml.isEmpty() && content instanceof String cs) {
                var crumbs = new StringBuilder();
                for (var p : ml) {
                    if (p instanceof String ps && !ps.isEmpty()) {
                        if (!crumbs.isEmpty()) crumbs.append("\n");
                        crumbs.append(imagePlaceholderText(ps));
                    }
                }
                if (!crumbs.isEmpty()) {
                    content = cs.isEmpty() ? crumbs.toString() : cs + "\n" + crumbs;
                }
            }

            // Synthesize CLI app breadcrumbs
            var cliApps = msg.get("cli_apps");
            if ("user".equals(role) && cliApps instanceof List<?> cl && !cl.isEmpty() && content instanceof String cs) {
                var lines = new ArrayList<String>();
                int count = 0;
                for (var item : cl) {
                    if (count >= 8) break;
                    if (item instanceof Map<?, ?> im) {
                        var name = strVal(im.get("name")).trim().toLowerCase();
                        if (name.isEmpty()) continue;
                        var entryPoint = strVal(im.get("entry_point"), "unknown").trim();
                        if (entryPoint.isEmpty()) entryPoint = "unknown";
                        lines.add("[CLI App Attachment: @" + name + "; tool=run_cli_app; entry_point="
                                + entryPoint + "; skill=skills/cli-app-" + name + "/SKILL.md]");
                    }
                }
                if (!lines.isEmpty()) {
                    content = cs.isEmpty() ? String.join("\n", lines) : cs + "\n" + String.join("\n", lines);
                }
            }

            // Synthesize MCP preset breadcrumbs
            var mcpPresets = msg.get("mcp_presets");
            if ("user".equals(role) && mcpPresets instanceof List<?> mpl && !mpl.isEmpty() && content instanceof String cs) {
                var lines = new ArrayList<String>();
                int count = 0;
                for (var item : mpl) {
                    if (count >= 8) break;
                    if (item instanceof Map<?, ?> im) {
                        var name = strVal(im.get("name")).trim().toLowerCase();
                        if (name.isEmpty()) continue;
                        var transport = strVal(im.get("transport"), "mcp").trim();
                        if (transport.isEmpty()) transport = "mcp";
                        lines.add("[MCP Preset Attachment: @" + name + "; tool_prefix=mcp_" + name + "_; transport="
                                + transport + "]");
                    }
                }
                if (!lines.isEmpty()) {
                    content = cs.isEmpty() ? String.join("\n", lines) : cs + "\n" + String.join("\n", lines);
                }
            }

            // Annotate message time on user messages
            if (includeTimestamps) {
                content = annotateMessageTime(msg, content);
            }

            // Skip empty assistant messages without tool_calls/reasoning
            if ("assistant".equals(role) && content instanceof String cs && cs.trim().isEmpty()) {
                if (msg.get("tool_calls") == null && msg.get("reasoning_content") == null
                        && msg.get("thinking_blocks") == null) {
                    continue;
                }
            }

            var entry = new LinkedHashMap<String, Object>();
            entry.put("role", msg.get("role"));
            entry.put("content", content);
            for (var key : List.of("tool_calls", "tool_call_id", "name", "reasoning_content", "thinking_blocks")) {
                if (msg.containsKey(key)) {
                    entry.put(key, msg.get(key));
                }
            }
            out.add(entry);
        }

        // Token budget slicing from the tail
        if (maxTokens > 0 && !out.isEmpty()) {
            var kept = new ArrayList<Map<String, Object>>();
            int used = 0;
            for (int i = out.size() - 1; i >= 0; i--) {
                int tokens = estimateMessageTokens(out.get(i));
                if (!kept.isEmpty() && used + tokens > maxTokens) {
                    break;
                }
                kept.addFirst(out.get(i));
                used += tokens;
            }

            // Keep aligned to first user turn
            int firstUser = -1;
            for (int i = 0; i < kept.size(); i++) {
                if ("user".equals(kept.get(i).get("role"))) {
                    firstUser = i;
                    break;
                }
            }
            if (firstUser >= 0) {
                kept = new ArrayList<>(kept.subList(firstUser, kept.size()));
            } else {
                // Recover nearest user from original output
                int recoveredUser = -1;
                for (int i = out.size() - 1; i >= 0; i--) {
                    if ("user".equals(out.get(i).get("role"))) {
                        recoveredUser = i;
                        break;
                    }
                }
                if (recoveredUser >= 0) {
                    kept = new ArrayList<>(out.subList(recoveredUser, out.size()));
                }
            }

            // Keep legal tool-call boundary
            int legalStart = findLegalMessageStart(kept);
            if (legalStart > 0) {
                kept = new ArrayList<>(kept.subList(legalStart, kept.size()));
            }
            out = kept;
        }

        return out;
    }

    // -- clear --

    public void clear() {
        messages.clear();
        lastConsolidated = 0;
        updatedAt = Instant.now();
        metadata.remove("_last_summary");
    }

    // -- retainRecentLegalSuffix --

    public Map<String, Object> retainRecentLegalSuffix(int maxMessages) {
        if (maxMessages <= 0) {
            var dropped = List.<Map<String, Object>>copyOf(messages);
            int lc = lastConsolidated;
            clear();
            return Map.of("dropped", dropped, "alreadyConsolidated", Math.min(lc, dropped.size()));
        }
        if (messages.size() <= maxMessages) {
            return Map.of("dropped", List.of(), "alreadyConsolidated", 0);
        }

        var original = List.<Map<String, Object>>copyOf(messages);
        int beforeLc = lastConsolidated;

        var retained = new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));

        // Prefer starting at a user turn
        int firstUser = -1;
        for (int i = 0; i < retained.size(); i++) {
            if ("user".equals(retained.get(i).get("role"))) {
                firstUser = i;
                break;
            }
        }
        if (firstUser >= 0) {
            retained = new ArrayList<>(retained.subList(firstUser, retained.size()));
        } else {
            // Anchor to latest user in full session
            int latestUser = -1;
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(messages.get(i).get("role"))) {
                    latestUser = i;
                    break;
                }
            }
            if (latestUser >= 0) {
                int end = Math.min(latestUser + maxMessages, messages.size());
                retained = new ArrayList<>(messages.subList(latestUser, end));
            }
        }

        // Mirror getHistory: drop orphan tool results at front
        int legalStart = findLegalMessageStart(retained);
        if (legalStart > 0) {
            retained = new ArrayList<>(retained.subList(legalStart, retained.size()));
        }

        // Hard cap
        if (retained.size() > maxMessages) {
            retained = new ArrayList<>(retained.subList(retained.size() - maxMessages, retained.size()));
            int ls = findLegalMessageStart(retained);
            if (ls > 0) {
                retained = new ArrayList<>(retained.subList(ls, retained.size()));
            }
        }

        // Compute dropped using identity
        var retainedIds = new java.util.HashSet<Integer>();
        for (var m : retained) {
            retainedIds.add(System.identityHashCode(m));
        }
        var dropped = new ArrayList<Map<String, Object>>();
        for (var m : original) {
            if (!retainedIds.contains(System.identityHashCode(m))) {
                dropped.add(m);
            }
        }

        int alreadyConsolidated = 0;
        for (int i = 0; i < original.size(); i++) {
            if (i < beforeLc && !retainedIds.contains(System.identityHashCode(original.get(i)))) {
                alreadyConsolidated++;
            }
        }

        int newLc = 0;
        for (int i = 0; i < original.size(); i++) {
            if (i < beforeLc && retainedIds.contains(System.identityHashCode(original.get(i)))) {
                newLc++;
            }
        }

        messages.clear();
        messages.addAll(retained);
        lastConsolidated = newLc;
        updatedAt = Instant.now();
        return Map.of("dropped", dropped, "alreadyConsolidated", alreadyConsolidated);
    }

    // -- enforceFileCap --

    public void enforceFileCap(Consumer<List<Map<String, Object>>> onArchive, int limit) {
        if (limit <= 0 || messages.size() <= limit) {
            return;
        }
        @SuppressWarnings("unchecked")
        var result = (Map<String, Object>) retainRecentLegalSuffix(limit);
        @SuppressWarnings("unchecked")
        var dropped = (List<Map<String, Object>>) result.get("dropped");
        int alreadyConsolidated = (int) result.get("alreadyConsolidated");
        if (dropped.isEmpty()) {
            return;
        }
        var archiveChunk = new ArrayList<>(dropped.subList(alreadyConsolidated, dropped.size()));
        if (!archiveChunk.isEmpty() && onArchive != null) {
            onArchive.accept(archiveChunk);
        }
    }

    // -- truncateMessages --

    public void truncateMessages(int maxMessages) {
        while (messages.size() > maxMessages) {
            messages.remove(0);
        }
        if (lastConsolidated > messages.size()) {
            lastConsolidated = messages.size();
        }
    }

    // -- static helpers --

    public static int findLegalMessageStart(List<Map<String, Object>> messages) {
        var declared = new java.util.HashSet<String>();
        int start = 0;
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            var role = msg.get("role");
            if ("assistant".equals(role)) {
                var toolCalls = msg.get("tool_calls");
                if (toolCalls instanceof List<?> tcList) {
                    for (var tc : tcList) {
                        if (tc instanceof Map<?, ?> tcm) {
                            var id = tcm.get("id");
                            if (id != null) {
                                declared.add(String.valueOf(id));
                            }
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                var tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }

    public static int estimateMessageTokens(Map<String, Object> message) {
        var parts = new ArrayList<String>();
        var content = message.get("content");
        if (content instanceof String s) {
            parts.add(s);
        } else if (content instanceof List<?> cl) {
            for (var part : cl) {
                if (part instanceof Map<?, ?> pm && "text".equals(pm.get("type"))) {
                    var text = pm.get("text");
                    if (text instanceof String ts && !ts.isEmpty()) {
                        parts.add(ts);
                    }
                } else {
                    parts.add(String.valueOf(part));
                }
            }
        } else if (content != null) {
            parts.add(String.valueOf(content));
        }
        for (var key : List.of("name", "tool_call_id")) {
            var value = message.get(key);
            if (value instanceof String sv && !sv.isEmpty()) {
                parts.add(sv);
            }
        }
        var toolCalls = message.get("tool_calls");
        if (toolCalls instanceof List<?> tcl && !tcl.isEmpty()) {
            parts.add(tcl.toString());
        }
        // ~4 chars per token
        int total = String.join("", parts).length();
        return Math.max(1, total / 4);
    }

    static String sanitizeAssistantReplayText(String content) {
        content = MESSAGE_TIME_PREFIX.matcher(content).replaceFirst("");
        var lines = content.split("\n");
        var kept = new ArrayList<String>();
        for (var line : lines) {
            if (!LOCAL_IMAGE_BREADCRUMB.matcher(line).matches()
                    && !TOOL_CALL_ECHO.matcher(line).matches()) {
                kept.add(line);
            }
        }
        return String.join("\n", kept).trim();
    }

    static Object annotateMessageTime(Map<String, Object> msg, Object content) {
        var timestamp = msg.get("timestamp");
        if (timestamp == null || !(content instanceof String s)) {
            return content;
        }
        if (!"user".equals(msg.get("role"))) {
            return content;
        }
        return "[Message Time: " + timestamp + "]\n" + s;
    }

    static String imagePlaceholderText(String path) {
        return path != null && !path.isEmpty() ? "[image: " + path + "]" : "[image]";
    }

    private static String strVal(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String strVal(Object value, String defaultVal) {
        return value == null ? defaultVal : String.valueOf(value);
    }
}
