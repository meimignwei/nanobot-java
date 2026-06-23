package com.nanobot.agent;

/**
 * Agent 单轮 turn 的状态枚举，驱动状态机转换。
 *
 * <p>对标 Python {@code nanobot/agent/loop.py} TurnState enum（RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE）。
 */
public enum TurnState {
    RESTORE,
    COMPACT,
    COMMAND,
    BUILD,
    RUN,
    SAVE,
    RESPOND,
    DONE
}
