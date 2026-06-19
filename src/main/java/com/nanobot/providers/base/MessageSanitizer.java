package com.nanobot.providers.base;

import java.util.*;

/**
 * Message sanitization utilities ported from LLMProvider static methods in Python base.py.
 * All methods are pure functions — no side effects on input.
 */
public final class MessageSanitizer {

    private MessageSanitizer() {}

    static final String SYNTHETIC_USER_CONTENT = "(conversation continued)";

    /** Build an image placeholder string. Mirrors image_placeholder_text() in helpers.py. */
    public static String imagePlaceholderText(String path, String empty) {
        return (path != null && !path.isEmpty()) ? "[image: " + path + "]" : empty;
    }

    // ------------------------------------------------------------------
    // _sanitize_empty_content
    // ------------------------------------------------------------------

    /**
     * Sanitize message content: fix empty blocks, strip internal _meta fields.
     * Mirrors Python _sanitize_empty_content() exactly.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sanitizeEmptyContent(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");

            if (content instanceof String s && s.isEmpty()) {
                Map<String, Object> clean = new LinkedHashMap<>(msg);
                boolean isAssistantWithTools = "assistant".equals(msg.get("role")) && msg.get("tool_calls") != null;
                clean.put("content", isAssistantWithTools ? null : "(empty)");
                result.add(clean);
                continue;
            }

            if (content instanceof List<?> items) {
                List<Object> newItems = new ArrayList<>();
                boolean changed = false;
                for (Object item : items) {
                    if (item instanceof Map<?, ?> itemMap) {
                        Map<String, Object> im = (Map<String, Object>) itemMap;
                        if (List.of("text", "input_text", "output_text").contains(im.get("type"))
                                && "".equals(im.get("text"))) {
                            changed = true;
                            continue;
                        }
                        if (im.containsKey("_meta")) {
                            Map<String, Object> stripped = new LinkedHashMap<>();
                            for (var entry : im.entrySet()) {
                                if (!"_meta".equals(entry.getKey())) {
                                    stripped.put(entry.getKey(), entry.getValue());
                                }
                            }
                            newItems.add(stripped);
                            changed = true;
                        } else {
                            newItems.add(item);
                        }
                    } else {
                        newItems.add(item);
                    }
                }
                if (changed) {
                    Map<String, Object> clean = new LinkedHashMap<>(msg);
                    if (!newItems.isEmpty()) {
                        clean.put("content", newItems);
                    } else if ("assistant".equals(msg.get("role")) && msg.get("tool_calls") != null) {
                        clean.put("content", null);
                    } else {
                        clean.put("content", "(empty)");
                    }
                    result.add(clean);
                    continue;
                }
            }

            if (content instanceof Map) {
                Map<String, Object> clean = new LinkedHashMap<>(msg);
                clean.put("content", List.of(content));
                result.add(clean);
                continue;
            }

            result.add(msg);
        }
        return result;
    }

    // ------------------------------------------------------------------
    // _enforce_role_alternation
    // ------------------------------------------------------------------

    /**
     * Merge consecutive same-role messages and drop trailing assistant messages.
     * Mirrors Python _enforce_role_alternation() exactly.
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> enforceRoleAlternation(List<Map<String, Object>> messages) {
        if (messages.isEmpty()) return messages;

        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            if (!merged.isEmpty()
                    && !"system".equals(role)
                    && !"tool".equals(role)
                    && role.equals(merged.get(merged.size() - 1).get("role"))
                    && ("user".equals(role) || "assistant".equals(role))) {

                Map<String, Object> prev = merged.get(merged.size() - 1);
                if ("assistant".equals(role)) {
                    boolean prevHasTools = prev.get("tool_calls") != null;
                    boolean currHasTools = msg.get("tool_calls") != null;
                    if (currHasTools) {
                        merged.set(merged.size() - 1, new LinkedHashMap<>(msg));
                        continue;
                    }
                    if (prevHasTools) continue;
                }
                Object prevContent = prev.getOrDefault("content", "");
                Object currContent = msg.getOrDefault("content", "");
                if (prevContent instanceof String ps && currContent instanceof String cs) {
                    Map<String, Object> updated = new LinkedHashMap<>(prev);
                    updated.put("content", (ps + "\n\n" + cs).strip());
                    merged.set(merged.size() - 1, updated);
                } else {
                    merged.set(merged.size() - 1, new LinkedHashMap<>(msg));
                }
            } else {
                merged.add(new LinkedHashMap<>(msg));
            }
        }

        Map<String, Object> lastPopped = null;
        while (!merged.isEmpty() && "assistant".equals(merged.get(merged.size() - 1).get("role"))) {
            lastPopped = merged.remove(merged.size() - 1);
        }

        if (!merged.isEmpty() && lastPopped != null
                && merged.stream().noneMatch(m -> "user".equals(m.get("role")) || "tool".equals(m.get("role")))) {
            Map<String, Object> recovered = new LinkedHashMap<>(lastPopped);
            recovered.put("role", "user");
            merged.add(recovered);
        }

        // Safety net: ensure first non-system message is not bare assistant
        for (int i = 0; i < merged.size(); i++) {
            Map<String, Object> msg = merged.get(i);
            if (!"system".equals(msg.get("role"))) {
                if ("assistant".equals(msg.get("role")) && msg.get("tool_calls") == null) {
                    merged.add(i, Map.of("role", "user", "content", SYNTHETIC_USER_CONTENT));
                }
                break;
            }
        }

        return merged;
    }

    // ------------------------------------------------------------------
    // _strip_image_content / _strip_image_content_inplace
    // ------------------------------------------------------------------

    /**
     * Replace image_url blocks with text placeholder. Returns null if no images found.
     * Mirrors Python _strip_image_content().
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> stripImageContent(List<Map<String, Object>> messages) {
        boolean found = false;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof List<?> blocks) {
                List<Object> newContent = new ArrayList<>();
                for (Object b : blocks) {
                    if (b instanceof Map<?, ?> bm) {
                        Map<String, Object> blockMap = (Map<String, Object>) bm;
                        if ("image_url".equals(blockMap.get("type"))) {
                            Map<String, Object> meta = (Map<String, Object>) blockMap.get("_meta");
                            String path = meta != null ? (String) meta.get("path") : null;
                            String placeholder = imagePlaceholderText(path, "[image omitted]");
                            newContent.add(Map.of("type", "text", "text", placeholder));
                            found = true;
                        } else {
                            newContent.add(b);
                        }
                    } else {
                        newContent.add(b);
                    }
                }
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newContent);
                result.add(newMsg);
            } else {
                result.add(msg);
            }
        }
        return found ? result : null;
    }

    /**
     * Replace image_url blocks with text placeholder IN PLACE.
     * Mutates the original message dicts. Returns true if any images were found.
     * Mirrors Python _strip_image_content_inplace().
     */
    @SuppressWarnings("unchecked")
    public static boolean stripImageContentInPlace(List<Map<String, Object>> messages) {
        boolean found = false;
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");
            if (content instanceof List<?> contentList) {
                List<Object> mutable = new ArrayList<>(contentList);
                for (int i = 0; i < mutable.size(); i++) {
                    Object b = mutable.get(i);
                    if (b instanceof Map<?, ?> bm) {
                        Map<String, Object> blockMap = (Map<String, Object>) bm;
                        if ("image_url".equals(blockMap.get("type"))) {
                            Map<String, Object> meta = (Map<String, Object>) blockMap.get("_meta");
                            String path = meta != null ? (String) meta.get("path") : null;
                            String placeholder = imagePlaceholderText(path, "[image omitted]");
                            mutable.set(i, Map.of("type", "text", "text", placeholder));
                            found = true;
                        }
                    }
                }
                msg.put("content", mutable);
            }
        }
        return found;
    }

    // ------------------------------------------------------------------
    // _sanitize_request_messages
    // ------------------------------------------------------------------

    /**
     * Keep only provider-safe message keys and normalize assistant content.
     * Mirrors Python _sanitize_request_messages().
     */
    public static List<Map<String, Object>> sanitizeRequestMessages(
            List<Map<String, Object>> messages,
            Set<String> allowedKeys
    ) {
        List<Map<String, Object>> sanitized = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Map<String, Object> clean = new LinkedHashMap<>();
            for (var entry : msg.entrySet()) {
                if (allowedKeys.contains(entry.getKey())) {
                    clean.put(entry.getKey(), entry.getValue());
                }
            }
            if ("assistant".equals(clean.get("role")) && !clean.containsKey("content")) {
                clean.put("content", null);
            }
            sanitized.add(clean);
        }
        return sanitized;
    }

    // ------------------------------------------------------------------
    // _tool_name / _tool_cache_marker_indices
    // ------------------------------------------------------------------

    /** Extract tool name from either OpenAI or Anthropic-style tool schemas. */
    public static String toolName(Map<String, Object> tool) {
        Object name = tool.get("name");
        if (name instanceof String s) return s;
        Object fn = tool.get("function");
        if (fn instanceof Map<?, ?> fnMap) {
            Object fname = fnMap.get("name");
            if (fname instanceof String s) return s;
        }
        return "";
    }

    /**
     * Return cache marker indices: builtin/MCP boundary and tail index.
     * Mirrors Python _tool_cache_marker_indices().
     */
    public static List<Integer> toolCacheMarkerIndices(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) return List.of();

        int tailIdx = tools.size() - 1;
        Integer lastBuiltinIdx = null;
        for (int i = tailIdx; i >= 0; i--) {
            if (!toolName(tools.get(i)).startsWith("mcp_")) {
                lastBuiltinIdx = i;
                break;
            }
        }

        List<Integer> orderedUnique = new ArrayList<>();
        for (Integer idx : new Integer[]{lastBuiltinIdx, tailIdx}) {
            if (idx != null && !orderedUnique.contains(idx)) {
                orderedUnique.add(idx);
            }
        }
        return orderedUnique;
    }
}
