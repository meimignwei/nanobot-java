package com.nanobot.agent.tools.impl;

import java.util.*;
import java.util.stream.Collectors;

/**
 * difflib 等价工具方法，提供字符串相似度比较、近似匹配搜索和 unified diff 生成。
 *
 * <p>对标 Python {@code difflib.SequenceMatcher}、{@code get_close_matches} 和
 * {@code unified_diff}。
 */
final class DiffUtil {

    private DiffUtil() {}

    /**
     * 计算两个序列的 Ratcliff/Obershelp 相似度。
     * ratio = 2.0 * matching_chars / (len(a) + len(b))
     *
     * @param a 序列 a
     * @param b 序列 b
     * @return 0.0 到 1.0 的相似度
     */
    // 对标 Python difflib.SequenceMatcher.ratio()
    static double ratio(List<String> a, List<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int matches = matchingChars(a, b, 0, a.size(), 0, b.size());
        int totalLen = totalLength(a) + totalLength(b);
        return totalLen == 0 ? 1.0 : 2.0 * matches / (double) totalLen;
    }

    /** 计算两个字符串的字符级相似度。 */
    static double strRatio(String a, String b) {
        List<String> al = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) al.add(String.valueOf(a.charAt(i)));
        List<String> bl = new ArrayList<>();
        for (int i = 0; i < b.length(); i++) bl.add(String.valueOf(b.charAt(i)));
        return ratio(al, bl);
    }

    private static int matchingChars(List<String> a, List<String> b,
                                      int aLo, int aHi, int bLo, int bHi) {
        // 找最长公共子块
        int bestLen = 0;
        int bestI = -1, bestJ = -1;
        for (int i = aLo; i < aHi; i++) {
            for (int j = bLo; j < bHi; j++) {
                int k = 0;
                while (i + k < aHi && j + k < bHi
                        && a.get(i + k).equals(b.get(j + k))) {
                    k++;
                }
                if (k > bestLen) {
                    bestLen = k;
                    bestI = i;
                    bestJ = j;
                }
            }
        }
        if (bestLen == 0) return 0;
        int matches = totalLength(a.subList(bestI, bestI + bestLen));
        if (bestI > aLo && bestJ > bLo) {
            matches += matchingChars(a, b, aLo, bestI, bLo, bestJ);
        }
        if (bestI + bestLen < aHi && bestJ + bestLen < bHi) {
            matches += matchingChars(a, b,
                    bestI + bestLen, aHi, bestJ + bestLen, bHi);
        }
        return matches;
    }

    private static int totalLength(List<String> seq) {
        return seq.stream().mapToInt(String::length).sum();
    }

    /**
     * 从候选列表中找出最接近 word 的前 n 个匹配项。
     *
     * @param word       目标词
     * @param candidates 候选列表
     * @param n          最大返回数
     * @param cutoff     最小相似度阈值（0.0-1.0）
     * @return 按相似度降序排列的近似匹配列表
     */
    // 对标 Python difflib.get_close_matches()
    static List<String> getCloseMatches(String word, List<String> candidates,
                                         int n, double cutoff) {
        if (candidates.isEmpty()) return List.of();
        return candidates.stream()
                .map(c -> new Object() {
                    final String w = c;
                    final double r = strRatio(word.toLowerCase(), c.toLowerCase());
                })
                .filter(x -> x.r >= cutoff)
                .sorted((a, b) -> Double.compare(b.r, a.r))
                .limit(n)
                .map(x -> x.w)
                .collect(Collectors.toList());
    }

    /**
     * 生成 unified diff 格式的字符串。
     *
     * @param aLines 旧文件行列表
     * @param bLines 新文件行列表
     * @param fromFile 旧文件名标签
     * @param toFile   新文件名标签
     * @return unified diff 字符串
     */
    // 对标 Python difflib.unified_diff()
    static String unifiedDiff(List<String> aLines, List<String> bLines,
                               String fromFile, String toFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(fromFile != null ? fromFile : "old_text (provided)").append('\n');
        sb.append("+++ ").append(toFile).append('\n');

        // 简单的逐行 LCS diff
        int[][] dp = new int[aLines.size() + 1][bLines.size() + 1];
        for (int i = aLines.size() - 1; i >= 0; i--) {
            for (int j = bLines.size() - 1; j >= 0; j--) {
                if (aLines.get(i).equals(bLines.get(j))) {
                    dp[i][j] = 1 + dp[i + 1][j + 1];
                } else {
                    dp[i][j] = Math.max(dp[i + 1][j], dp[i][j + 1]);
                }
            }
        }

        int i = 0, j = 0, lastPrinted = -2;
        while (i < aLines.size() || j < bLines.size()) {
            if (i < aLines.size() && j < bLines.size()
                    && aLines.get(i).equals(bLines.get(j))) {
                if (i > lastPrinted + 1) {
                    sb.append("@@ -").append(i + 1).append(" +")
                            .append(j + 1).append(" @@\n");
                }
                sb.append(' ').append(aLines.get(i));
                if (!aLines.get(i).endsWith("\n")) sb.append('\n');
                lastPrinted = i;
                i++; j++;
            } else if (j < bLines.size()
                    && (i >= aLines.size() || dp[i][j + 1] >= dp[i + 1][j])) {
                sb.append('+').append(bLines.get(j));
                if (!bLines.get(j).endsWith("\n")) sb.append('\n');
                lastPrinted = i - 1;
                j++;
            } else {
                sb.append('-').append(aLines.get(i));
                if (!aLines.get(i).endsWith("\n")) sb.append('\n');
                lastPrinted = i;
                i++;
            }
        }
        return sb.toString();
    }
}
