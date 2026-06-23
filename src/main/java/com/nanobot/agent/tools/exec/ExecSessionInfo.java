package com.nanobot.agent.tools.exec;

/**
 * 执行会话摘要信息记录，供列表展示使用。
 *
 * <p>对标 Python {@code exec_session.py ExecSessionInfo}（lines 42-51）。
 * 包含会话标识、命令、工作目录、耗时/空闲/剩余时间、退出码和所属 session key。
 *
 * @param sessionId        会话 ID（12 位 hex）
 * @param command          执行的命令
 * @param cwd              工作目录
 * @param elapsedSec       已运行秒数
 * @param idleSec          空闲秒数
 * @param remainingSec     剩余超时秒数
 * @param returncode       退出码（运行中为 null）
 * @param ownerSessionKey  所属 session key
 */
public record ExecSessionInfo(
        String sessionId,
        String command,
        String cwd,
        double elapsedSec,
        double idleSec,
        double remainingSec,
        Integer returncode,
        String ownerSessionKey
) {}
