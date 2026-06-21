package com.nanobot.utils;

/**
 * 单行代码年龄（距上次修改的天数）。
 * 对应 Python LineAge dataclass（utils/gitstore.py:28-32）。
 */
public record LineAge(int ageDays) {}
