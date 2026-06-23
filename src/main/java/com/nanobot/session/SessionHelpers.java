package com.nanobot.session;

import java.util.*;

/**
 * 会话消息列表内部操作工具（包私有）。
 *
 * <p>对标 Python {@code nanobot/session/manager.py} 模块级辅助函数。
 */
final class SessionHelpers {

    private SessionHelpers() {}

    /**
     * 验证 lastConsolidated 是否在有效范围内。
     * 对标 Python Session.__post_init__。
     */
    static boolean isValidConsolidated(int v, int msgCount) {
        return v >= 0 && v <= msgCount;
    }

    /**
     * 取列表尾部最多 count 个元素。
     * 对标 Python tail 切片 list[-count:]。
     */
    static List<Map<String, Object>> tailSublist(List<Map<String, Object>> list, int count) {
        int start = Math.max(0, list.size() - count);
        return new ArrayList<>(list.subList(start, list.size()));
    }

    /**
     * 将切片对齐到 user turn 起始（含前置 _channel_delivery）。
     * 对标 Python get_history 中的对齐逻辑。
     */
    static List<Map<String, Object>> alignToUserTurn(List<Map<String, Object>> sliced) {
        for (int i = 0; i < sliced.size(); i++) {
            if ("user".equals(sliced.get(i).get("role"))) {
                int s = (i > 0 && Boolean.TRUE.equals(sliced.get(i - 1).get("_channel_delivery"))) ? i - 1 : i;
                return (s > 0) ? new ArrayList<>(sliced.subList(s, sliced.size())) : sliced;
            }
        }
        return sliced;
    }

    /**
     * 找到消息列表中第一个合法消息的下标——其 tool 结果有匹配的 assistant tool_calls。
     * 遍历消息，跟踪 assistant 声明的 tool_call ID，遇到 tool 消息的 tool_call_id
     * 未在已声明集合中时重置起点并清空声明集合。
     * 对标 Python find_legal_message_start()。
     */
    @SuppressWarnings("unchecked")
    static int findLegalMessageStart(List<Map<String, Object>> list) {
        Set<String> declared = new HashSet<>();
        int start = 0;
        for (int i = 0; i < list.size(); i++) {
            Map<String, Object> msg = list.get(i);
            String role = Objects.toString(msg.get("role"), "");
            if ("assistant".equals(role)) {
                List<Map<String, Object>> tcs = (List<Map<String, Object>>) msg.get("tool_calls");
                if (tcs != null) {
                    for (Map<String, Object> tc : tcs) {
                        if (tc != null && tc.get("id") != null) {
                            declared.add(String.valueOf(tc.get("id")));
                        }
                    }
                }
            } else if ("tool".equals(role)) {
                Object tid = msg.get("tool_call_id");
                if (tid != null && !declared.contains(String.valueOf(tid))) {
                    start = i + 1;
                    declared.clear();
                }
            }
        }
        return start;
    }

    /**
     * 找到列表中第一条指定 role 的索引。
     * 对标 Python find_first_role()。
     */
    static int findFirstRole(List<Map<String, Object>> list, String target) {
        for (int i = 0; i < list.size(); i++) {
            if (target.equals(list.get(i).get("role"))) return i;
        }
        return -1;
    }

    /**
     * 找到列表中最后一条指定 role 的索引。
     * 对标 Python find_last_role()。
     */
    static int findLastRole(List<Map<String, Object>> list, String target) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (target.equals(list.get(i).get("role"))) return i;
        }
        return -1;
    }

    /**
     * 构建基于 identity hash 的去重集合——对标 Python 用 id() 做差集。
     */
    static Set<Integer> identitySet(List<Map<String, Object>> list) {
        Set<Integer> s = new HashSet<>();
        for (Map<String, Object> m : list) s.add(System.identityHashCode(m));
        return s;
    }
}
