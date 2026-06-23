package com.nanobot.agent.tools.exec;

/**
 * 执行会话轮询结果记录。
 *
 * <p>对标 Python {@code exec_session.py _SessionPoll}（lines 31-39）。
 * 包含输出、退出状态、耗时、超时/终止/截断标记等字段。
 *
 * @param output         最近的 stdout/stderr 输出
 * @param done           进程是否已退出
 * @param exitCode       进程退出码（运行中为 null）
 * @param elapsedSec     会话启动以来的秒数
 * @param timedOut       是否因超时被终止
 * @param terminated     是否被显式终止
 * @param stdinClosed    此轮交互中是否关闭了 stdin
 * @param truncatedChars 输出中被省略的字符数
 */
public record SessionPoll(
        String output,
        boolean done,
        Integer exitCode,
        double elapsedSec,
        boolean timedOut,
        boolean terminated,
        boolean stdinClosed,
        int truncatedChars
) {}
