package com.nanobot.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nullable;
import java.util.*;

/**
 * 持续目标（sustained goal）的会话元数据辅助工具。
 *
 * <p>对标 Python {@code nanobot/session/goal_state.py} — 解析 goal_state metadata、
 * 生成 LLM 上下文行和 WebSocket 快照。
 */
public final class GoalState {

    private GoalState() {}

    /** 对标 Python GOAL_STATE_KEY。 */
    public static final String GOAL_STATE_KEY = "goal_state";

    private static final String LEGACY_KEY = "thread_goal";
    private static final int MAX_RUNTIME_CHARS = 4000;
    private static final int MAX_WS_CHARS = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 从 metadata 中获取 goal_state 原始值，优先取新 key，回退到旧 key。
     * 对标 Python {@code _session_goal_raw(metadata)}。
     *
     * @param metadata 会话元数据
     * @return goal_state 原始值，可能为 null
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Object goalStateRaw(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return null;
        if (metadata.containsKey(GOAL_STATE_KEY)) return metadata.get(GOAL_STATE_KEY);
        return metadata.get(LEGACY_KEY);
    }

    /** 移除旧版 goal 键。对标 Python 中迁移后清理旧 key 的逻辑。 */
    public static void discardLegacyKey(Map<String, Object> metadata) {
        metadata.remove(LEGACY_KEY);
    }

    /**
     * 解析 goal_state blob 为 Map。
     * 对标 Python {@code parse_goal_state(blob)}。
     *
     * @param blob 原始值（Map 或 JSON 字符串）
     * @return 解析后的 Map，解析失败返回 null
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseGoalState(@Nullable Object blob) {
        if (blob == null) return null;
        if (blob instanceof Map) return (Map<String, Object>) blob;
        if (blob instanceof String s) {
            try {
                Object parsed = MAPPER.readValue(s, Object.class);
                return (parsed instanceof Map) ? (Map<String, Object>) parsed : null;
            } catch (JsonProcessingException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * 检查持续目标是否处于活跃状态。
     * 对标 Python {@code sustained_goal_active(metadata)}。
     *
     * @param metadata 会话元数据
     * @return 目标状态为 "active" 时返回 true
     */
    public static boolean sustainedGoalActive(@Nullable Map<String, Object> metadata) {
        Map<String, Object> goal = parseGoalState(goalStateRaw(metadata));
        return goal != null && "active".equals(goal.get("status"));
    }

    /**
     * 检查当前 turn 是否属于持续目标 turn（原命令为 /goal 或目标已激活）。
     * 对标 Python {@code sustained_goal_turn(metadata, *, message_metadata)}。
     *
     * @param metadata        会话元数据
     * @param messageMetadata 消息元数据
     * @return 属于持续目标 turn 时返回 true
     */
    public static boolean sustainedGoalTurn(
            @Nullable Map<String, Object> metadata,
            @Nullable Map<String, Object> messageMetadata) {
        if (sustainedGoalActive(metadata)) return true;
        if (messageMetadata == null) return false;
        return "/goal".equals(Objects.toString(
                messageMetadata.get("original_command"), "").strip());
    }

    /**
     * 生成供 LLM system prompt 使用的持续目标运行时描述行。
     * 对标 Python {@code goal_state_runtime_lines(metadata)}。
     *
     * @param metadata 会话元数据
     * @return 最多 MAX_RUNTIME_CHARS 的目标描述行列表
     */
    public static List<String> goalStateRuntimeLines(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return List.of();
        Map<String, Object> goal = parseGoalState(goalStateRaw(metadata));
        if (goal == null || !"active".equals(goal.get("status"))) return List.of();
        String obj = Objects.toString(goal.get("objective"), "").strip();
        if (obj.isEmpty()) return List.of("Goal: active (no objective text stored).");
        if (obj.length() > MAX_RUNTIME_CHARS) {
            obj = obj.substring(0, MAX_RUNTIME_CHARS).stripTrailing() + "\n… (truncated)";
        }
        List<String> out = new ArrayList<>();
        out.add("Goal (active):");
        out.add(obj);
        String hint = Objects.toString(goal.get("ui_summary"), "").strip();
        if (!hint.isEmpty()) out.add("Summary: " + hint);
        return out;
    }

    /**
     * 生成 WebSocket 可传输的 goal_state 快照。
     * 对标 Python {@code goal_state_ws_blob(metadata)}。
     *
     * @param metadata 会话元数据
     * @return 含 active 状态和目标摘要的 Map
     */
    public static Map<String, Object> goalStateWsBlob(@Nullable Map<String, Object> metadata) {
        if (metadata == null) return Map.of("active", false);
        Map<String, Object> goal = parseGoalState(goalStateRaw(metadata));
        if (goal == null || !"active".equals(goal.get("status"))) return Map.of("active", false);
        String obj = Objects.toString(goal.get("objective"), "").strip();
        if (obj.length() > MAX_WS_CHARS) obj = obj.substring(0, MAX_WS_CHARS).stripTrailing() + "…";
        String summary = Objects.toString(goal.get("ui_summary"), "").strip();
        if (summary.length() > 120) summary = summary.substring(0, 120);
        Map<String, Object> blob = new LinkedHashMap<>();
        blob.put("active", true);
        if (!summary.isEmpty()) blob.put("ui_summary", summary);
        if (!obj.isEmpty()) blob.put("objective", obj);
        return blob;
    }

    /**
     * 计算持续目标 turn 的 LLM 超时时间。
     * 持续目标 turn 返回 0.0（无超时），普通 turn 返回 null（使用默认值）。
     * 对标 Python {@code runner_wall_llm_timeout_s(...)}。
     *
     * @param sessions        会话管理器
     * @param sessionKey      会话键
     * @param metadata        会话元数据
     * @param messageMetadata 消息元数据
     * @return 0.0 表示持续目标 turn 无超时，null 表示使用默认值
     */
    @Nullable
    public static Double runnerWallLlmTimeoutS(
            SessionManager sessions, @Nullable String sessionKey,
            @Nullable Map<String, Object> metadata,
            @Nullable Map<String, Object> messageMetadata) {
        Map<String, Object> meta = metadata;
        if (meta == null && sessionKey != null) {
            meta = sessions.getOrCreate(sessionKey).getMetadata();
        }
        return sustainedGoalTurn(meta, messageMetadata) ? 0.0 : null;
    }
}
