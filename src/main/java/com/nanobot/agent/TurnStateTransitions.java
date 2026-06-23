package com.nanobot.agent;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TurnState 状态转换表，定义每个状态在收到不同事件时的下一状态。
 *
 * <p>对标 Python {@code nanobot/agent/loop.py} _TRANSITIONS 字典。
 */
public final class TurnStateTransitions {

    private static final EnumMap<TurnState, Map<String, TurnState>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(TurnState.class);
        TRANSITIONS.put(TurnState.RESTORE, mapOf("ok", TurnState.COMPACT));
        TRANSITIONS.put(TurnState.COMPACT, mapOf("ok", TurnState.COMMAND));
        TRANSITIONS.put(TurnState.COMMAND, mapOf("dispatch", TurnState.BUILD, "shortcut", TurnState.DONE));
        TRANSITIONS.put(TurnState.BUILD, mapOf("ok", TurnState.RUN));
        TRANSITIONS.put(TurnState.RUN, mapOf("ok", TurnState.SAVE));
        TRANSITIONS.put(TurnState.SAVE, mapOf("ok", TurnState.RESPOND));
        TRANSITIONS.put(TurnState.RESPOND, mapOf("ok", TurnState.DONE));
        TRANSITIONS.put(TurnState.DONE, new LinkedHashMap<>());
    }

    /**
     * 根据当前状态和事件获取下一状态。
     *
     * @param current 当前状态
     * @param event   转换事件名
     * @return 下一状态
     * @throws IllegalStateException 若无匹配的转换
     */
    public static TurnState next(TurnState current, String event) {
        Map<String, TurnState> nextStates = TRANSITIONS.get(current);
        TurnState next = nextStates.get(event);
        if (next == null) {
            throw new IllegalStateException(
                    "No transition for state " + current + " with event '" + event + "'");
        }
        return next;
    }

    private static Map<String, TurnState> mapOf(Object... pairs) {
        Map<String, TurnState> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (TurnState) pairs[i + 1]);
        }
        return map;
    }
}
