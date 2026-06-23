package com.nanobot.agent.tools;

import java.util.*;

/**
 * 工具参数的不可变快照及深拷贝工具方法。
 *
 * <p>对标 Python {@code nanobot/agent/tools/base.py tool_parameters()} 装饰器的
 * deepcopy 行为。在 Java 中，工具子类定义 {@code private static final Map}
 * 并在 {@code getParameters()} 中返回深拷贝，防止共享静态 Map 被意外修改。
 *
 * <pre>{@code
 * public class ReadFileTool extends Tool {
 *     private static final Map<String, Object> PARAMETERS =
 *         ToolParametersSchema.create(
 *             List.of("path"),
 *             null,
 *             Map.of(
 *                 "path", new StringSchema("The file path to read"),
 *                 "offset", new IntegerSchema("Line number to start reading from")
 *             )
 *         );
 *
 *     @Override
 *     public Map<String, Object> getParameters() {
 *         return ToolParameters.deepCopy(PARAMETERS);
 *     }
 * }
 * }</pre>
 */
public final class ToolParameters {

    private ToolParameters() {}

    /**
     * 对 JSON 安全的 Map 进行递归深拷贝，并包装为不可修改视图。
     *
     * @param original 原始 Map（仅含 String、Number、Boolean、List、Map 值）
     * @return 不可修改的深拷贝 Map
     */
    // 对标 Python copy.deepcopy() 的 parameters 防御性拷贝
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> original) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : original.entrySet()) {
            copy.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 递归深拷贝单个值。Map 和 List 递归拷贝，不可变类型（String、Integer、
     * Boolean 等）直接返回。
     *
     * @param val 待拷贝的值
     * @return 深拷贝后的值
     */
    private static Object deepCopyValue(Object val) {
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                copy.put((String) e.getKey(), deepCopyValue(e.getValue()));
            }
            return copy;
        }
        if (val instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return val; // 不可变类型：String、Integer、Boolean 等
    }
}
