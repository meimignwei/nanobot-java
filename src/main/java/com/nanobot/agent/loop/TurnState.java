package com.nanobot.agent.loop;

/**
 * Agent 状态机的状态枚举。
 * 对应 Python TurnState enum（loop.py 行 77-85）。
 *
 * <p>状态流转：RESTORE → COMPACT → COMMAND → BUILD → RUN → SAVE → RESPOND → DONE</p>
 */
public enum TurnState {
    /** 从磁盘恢复会话 */
    RESTORE,
    /** 压缩/consolidate 旧消息 */
    COMPACT,
    /** 执行内置命令（如 /new、/help） */
    COMMAND,
    /** 构建 LLM 消息上下文 */
    BUILD,
    /** 运行 agent 循环（LLM 推理 + 工具执行） */
    RUN,
    /** 持久化会话到磁盘 */
    SAVE,
    /** 发送回复到渠道 */
    RESPOND,
    /** 完成 */
    DONE
}
