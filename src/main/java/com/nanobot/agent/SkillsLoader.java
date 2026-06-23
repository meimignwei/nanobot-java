package com.nanobot.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent skill 加载器——从 workspace 和 builtin 目录发现、加载 SKILL.md 文件，
 * 并检查运行环境依赖（CLI 工具、环境变量）。
 *
 * <p>对标 Python {@code nanobot/agent/skills.py:21-260 class SkillsLoader}。
 */
public class SkillsLoader {

    /** 对标 Python _STRIP_SKILL_FRONTMATTER = re.compile(...) */
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^---\\s*\r?\n(.*?)\r?\n---\\s*\r?\n?", Pattern.DOTALL);

    private static final ObjectMapper YAML_MAPPER = new YAMLMapper();
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final Path workspace;
    private final Path workspaceSkills;
    private final Path builtinSkills;
    private final Set<String> disabledSkills;

    /**
     * 创建 SkillsLoader。
     * 对标 Python SkillsLoader.__init__(workspace, builtin_skills_dir=None, disabled_skills=None)。
     */
    public SkillsLoader(Path workspace, @Nullable Path builtinSkillsDir,
                        @Nullable Set<String> disabledSkills) {
        this.workspace = workspace;
        this.workspaceSkills = workspace.resolve("skills");
        this.builtinSkills = builtinSkillsDir != null
                ? builtinSkillsDir : Path.of("src/main/resources/skills");
        this.disabledSkills = disabledSkills != null
                ? new HashSet<>(disabledSkills) : new HashSet<>();
    }

    // ==================== 目录扫描 ====================

    /**
     * 从目录中枚举 SKILL.md 条目。
     * 对标 Python _skill_entries_from_dir(base, source, skip_names=None)。
     */
    private List<Map<String, String>> skillEntriesFromDir(Path base, String source,
                                                          @Nullable Set<String> skipNames) {
        if (!Files.exists(base)) return List.of();
        List<Map<String, String>> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(base)) {
            for (Path skillDir : stream) {
                if (!Files.isDirectory(skillDir)) continue;
                Path skillFile = skillDir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) continue;
                String name = skillDir.getFileName().toString();
                if (skipNames != null && skipNames.contains(name)) continue;
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("name", name);
                entry.put("path", skillFile.toString());
                entry.put("source", source);
                entries.add(entry);
            }
        } catch (IOException ignored) {}
        return entries;
    }

    // ==================== listing ====================

    /**
     * 列出所有可用 skill（workspace 优先于 builtin）。
     * 对标 Python list_skills(filter_unavailable=True)。
     */
    public List<Map<String, String>> listSkills(boolean filterUnavailable) {
        List<Map<String, String>> skills = skillEntriesFromDir(workspaceSkills, "workspace", null);
        Set<String> workspaceNames = skills.stream()
                .map(e -> e.get("name"))
                .collect(java.util.stream.Collectors.toSet());
        if (builtinSkills != null && Files.exists(builtinSkills)) {
            skills.addAll(skillEntriesFromDir(builtinSkills, "builtin", workspaceNames));
        }
        if (!disabledSkills.isEmpty()) {
            skills = skills.stream()
                    .filter(s -> !disabledSkills.contains(s.get("name")))
                    .toList();
        }
        if (filterUnavailable) {
            skills = skills.stream()
                    .filter(s -> checkRequirements(getSkillMeta(s.get("name"))))
                    .toList();
        }
        return skills;
    }

    // ==================== 加载 ====================

    /**
     * 按名称加载 SKILL.md 内容（workspace → builtin 顺序查找）。
     * 对标 Python load_skill(name)。
     */
    @Nullable
    public String loadSkill(String name) {
        List<Path> roots = new ArrayList<>();
        roots.add(workspaceSkills);
        if (builtinSkills != null) roots.add(builtinSkills);
        for (Path root : roots) {
            Path path = root.resolve(name).resolve("SKILL.md");
            if (Files.exists(path)) {
                try {
                    return Files.readString(path);
                } catch (IOException ignored) {}
            }
        }
        return null;
    }

    /**
     * 将多个 skill 的内容加载并格式化为 LLM 上下文。
     * 对标 Python load_skills_for_context(skill_names)。
     */
    public String loadSkillsForContext(List<String> skillNames) {
        List<String> parts = new ArrayList<>();
        for (String name : skillNames) {
            String markdown = loadSkill(name);
            if (markdown != null) {
                parts.add("### Skill: " + name + "\n\n" + stripFrontmatter(markdown));
            }
        }
        return String.join("\n\n---\n\n", parts);
    }

    /**
     * 构建所有 skill 的摘要列表（含可用性状态）。
     * 对标 Python build_skills_summary(exclude=None)。
     */
    public String buildSkillsSummary(@Nullable Set<String> exclude) {
        List<Map<String, String>> allSkills = listSkills(false);
        if (allSkills.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        for (Map<String, String> entry : allSkills) {
            String name = entry.get("name");
            if (exclude != null && exclude.contains(name)) continue;
            Map<String, Object> meta = getSkillMeta(name);
            boolean available = checkRequirements(meta);
            String desc = getSkillDescription(name);
            if (available) {
                lines.add("- **" + name + "** — " + desc + "  `" + entry.get("path") + "`");
            } else {
                String missing = getMissingRequirements(meta);
                String suffix = missing.isEmpty() ? " (unavailable)"
                        : " (unavailable: " + missing + ")";
                lines.add("- **" + name + "** — " + desc + suffix + "  `" + entry.get("path") + "`");
            }
        }
        return String.join("\n", lines);
    }

    // ==================== requirements check ====================

    /**
     * 获取缺失依赖项的 human-readable 描述。
     * 对标 Python _get_missing_requirements(skill_meta)。
     */
    private String getMissingRequirements(Map<String, Object> skillMeta) {
        Object requiresObj = skillMeta.get("requires");
        if (!(requiresObj instanceof Map<?, ?> requires)) return "";
        List<String> missing = new ArrayList<>();
        Object binsObj = requires.get("bins");
        Object envObj = requires.get("env");
        if (binsObj instanceof List<?> requiredBins) {
            for (Object cmd : requiredBins) {
                if (!commandExists(cmd.toString())) missing.add("CLI: " + cmd);
            }
        }
        if (envObj instanceof List<?> requiredEnvVars) {
            for (Object env : requiredEnvVars) {
                if (System.getenv(env.toString()) == null) missing.add("ENV: " + env);
            }
        }
        return String.join(", ", missing);
    }

    /**
     * 获取 skill 可用性（布尔 + 原因字符串）。
     * 对标 Python get_skill_availability(name)。
     */
    public Map.Entry<Boolean, String> getSkillAvailability(String name) {
        Map<String, Object> meta = getSkillMeta(name);
        boolean available = checkRequirements(meta);
        return Map.entry(available, available ? "" : getMissingRequirements(meta));
    }

    /**
     * 获取 skill 的依赖项详情。
     * 对标 Python get_skill_requirements(name)。
     */
    public Map<String, List<String>> getSkillRequirements(String name) {
        Map<String, Object> meta = getSkillMeta(name);
        Object requiresObj = meta.get("requires");
        Map<?, ?> requires = requiresObj instanceof Map<?, ?> m ? m : Map.of();
        List<String> bins = new ArrayList<>();
        if (requires.get("bins") instanceof List<?> l) {
            l.forEach(o -> bins.add(o.toString()));
        }
        List<String> env = new ArrayList<>();
        if (requires.get("env") instanceof List<?> l) {
            l.forEach(o -> env.add(o.toString()));
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        result.put("bins", bins);
        result.put("env", env);
        result.put("missing_bins", bins.stream().filter(b -> !commandExists(b)).toList());
        result.put("missing_env", env.stream().filter(e -> System.getenv(e) == null).toList());
        return result;
    }

    // ==================== metadata ====================

    /**
     * 从 SKILL.md frontmatter 中提取描述。
     * 对标 Python _get_skill_description(name)。
     */
    private String getSkillDescription(String name) {
        Map<String, Object> meta = getSkillMetadata(name);
        if (meta != null && meta.get("description") instanceof String desc) return desc;
        return name;
    }

    /**
     * 移除 YAML frontmatter。
     * 对标 Python _strip_frontmatter(content)
     * ——匹配 FRONTMATTER_PATTERN，返回匹配结束后的内容。
     */
    private String stripFrontmatter(String content) {
        if (!content.startsWith("---")) return content;
        Matcher m = FRONTMATTER_PATTERN.matcher(content);
        if (m.find()) return content.substring(m.end()).strip();
        return content;
    }

    /**
     * 解析 nanobot/openclaw 嵌套元数据（可能是 dict 或 JSON 字符串）。
     * 对标 Python _parse_nanobot_metadata(raw)。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseNanobotMetadata(Object raw) {
        Map<String, Object> data;
        if (raw instanceof Map<?, ?> map) {
            data = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                data.put(e.getKey().toString(), e.getValue());
            }
        } else if (raw instanceof String s) {
            try {
                data = JSON_MAPPER.readValue(s, new TypeReference<>() {});
            } catch (IOException e) {
                return Map.of();
            }
        } else {
            return Map.of();
        }
        if (data == null) return Map.of();
        Object payload = data.get("nanobot");
        if (payload == null) payload = data.get("openclaw");
        if (payload instanceof Map<?, ?> pmap) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : pmap.entrySet()) {
                result.put(e.getKey().toString(), e.getValue());
            }
            return result;
        }
        return Map.of();
    }

    /**
     * 检查 skill 依赖是否满足。
     * 对标 Python _check_requirements(skill_meta)。
     */
    private boolean checkRequirements(Map<String, Object> skillMeta) {
        Object requiresObj = skillMeta.get("requires");
        if (!(requiresObj instanceof Map<?, ?> requires)) return true;
        Object binsObj = requires.get("bins");
        Object envObj = requires.get("env");
        if (binsObj instanceof List<?> bins) {
            for (Object cmd : bins) {
                if (!commandExists(cmd.toString())) return false;
            }
        }
        if (envObj instanceof List<?> envVars) {
            for (Object v : envVars) {
                if (System.getenv(v.toString()) == null) return false;
            }
        }
        return true;
    }

    /**
     * 获取 skill 的 nanobot 子元数据。
     * 对标 Python _get_skill_meta(name)。
     */
    private Map<String, Object> getSkillMeta(String name) {
        Map<String, Object> rawMeta = getSkillMetadata(name);
        if (rawMeta == null) return Map.of();
        return parseNanobotMetadata(rawMeta.get("metadata"));
    }

    /**
     * 获取标记为 always=true 且满足依赖的 skill 列表。
     * 对标 Python get_always_skills()。
     */
    public List<String> getAlwaysSkills() {
        List<Map<String, String>> skills = listSkills(true);
        List<String> result = new ArrayList<>();
        for (Map<String, String> entry : skills) {
            String name = entry.get("name");
            Map<String, Object> meta = getSkillMetadata(name);
            if (meta == null) continue;
            boolean always = Boolean.TRUE.equals(
                    parseNanobotMetadata(meta.get("metadata")).get("always"))
                    || Boolean.TRUE.equals(meta.get("always"));
            if (always) result.add(name);
        }
        return result;
    }

    /**
     * 解析 SKILL.md 的 YAML frontmatter 为 Map。
     * 对标 Python get_skill_metadata(name) → dict | None。
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSkillMetadata(String name) {
        String content = loadSkill(name);
        if (content == null || !content.startsWith("---")) return null;
        String[] parts = content.split("---\\s*\\r?\\n?", 3);
        if (parts.length < 2) return null;
        try {
            return (Map<String, Object>) YAML_MAPPER.readValue(
                    parts[1], new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== which() ====================

    /**
     * 检查命令是否在 PATH 中可执行。
     * 对标 Python shutil.which(command)。
     */
    private static boolean commandExists(String command) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        String[] dirs = path.split(File.pathSeparator);
        for (String dir : dirs) {
            Path candidate = Path.of(dir, command);
            if (Files.exists(candidate)) return true;
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    if (Files.exists(Path.of(dir, command + ext))) return true;
                }
            }
        }
        return false;
    }
}
