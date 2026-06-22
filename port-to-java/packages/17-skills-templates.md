# Package 17: Skills + Templates (`src/main/resources/skills/` and `src/main/resources/templates/`)

## Overview

The `nanobot/skills/` and `nanobot/templates/` directories contain **static Markdown files** that are loaded into the agent's system prompt context at runtime. They are not executable code -- they are LLM context resources. In the Java port, these move to `src/main/resources/skills/` and `src/main/resources/templates/` on the classpath.

**Original Python files:** `nanobot/agent/skills.py` (261 lines), `nanobot/webui/skills_api.py` (62 lines), `nanobot/utils/prompt_templates.py` (36 lines), `nanobot/agent/context.py` (ContextBuilder, ~280 lines), `nanobot/utils/helpers.py` (`sync_workspace_templates()` + `load_bundled_template()`, ~80 lines), plus the 18 markdown files.
**Estimated Java code:** ~470 lines (SkillsLoader ~350 + SkillsController ~60 + PromptTemplates ~60). Plus ~1850 lines of markdown copied verbatim.

---

## 1. Skills (`src/main/resources/skills/`)

Each skill is a subdirectory under `skills/` containing a `SKILL.md` file with YAML frontmatter and markdown body. The 14 skills are:

### 1.1 Complete Skill Inventory

| # | Skill Name | Description | `always`? | Emoji | Requires |
|---|------------|-------------|-----------|-------|----------|
| 1 | `cron` | Schedule reminders and recurring tasks. Three modes: Reminder, Task, One-time. Supports `at`, `every`, and `cron_expr` with timezone. | No | None | -- |
| 2 | `github` | Interact with GitHub using the `gh` CLI. Issues, PRs, CI runs, advanced API queries with `--json`/`--jq`. | No | Octopus | `gh` binary |
| 3 | `image-generation` | Generate images via `generate_image` tool and iteratively edit saved image artifacts. Prompt rules, artifact rules, aspect ratios. | No | None | `generate_image` tool |
| 4 | `long-goal` | Sustained objectives via `long_task` / `complete_goal`. Idempotent goal wording, project-style modular work, early web/doc research, Runtime Context metadata. | No | None | `long_task` tool |
| 5 | `memory` | Two-layer memory system with Dream-managed knowledge files (`SOUL.md`, `USER.md`, `MEMORY.md`). Explains file structure and search commands. | **Yes** | None | -- |
| 6 | `clawhub` | Install/manage community-contributed skills from clawhub. | No | None | -- |
| 7 | `skill-creator` | Guide for creating new skills. | No | None | -- |
| 8 | `summarize` | Summarization conventions and patterns. | No | None | -- |
| 9 | `tmux` | Remote-control tmux sessions for interactive CLIs by sending keystrokes and scraping pane output. | No | Thread emoji | `tmux` binary |
| 10 | `update-setup` | Update nanobot setup/configuration. | No | None | -- |
| 11 | `weather` | Weather information lookup. | No | None | -- |
| 12 | `my` | Custom user-defined skill (placeholder for personal workflows). | No | None | -- |

### 1.2 SKILL.md Format

Every `SKILL.md` file has YAML frontmatter delimited by `---`:

```markdown
---
name: memory
description: Two-layer memory system with Dream-managed knowledge files.
always: true
---
# Memory

## Structure
...
```

The frontmatter fields:
- `name` -- Skill identifier (matches directory name)
- `description` -- One-line description
- `always` -- If `true`, loaded into every context regardless of tool availability
- `metadata` -- Optional JSON object (emoji, required binaries, install instructions)

### 1.3 Classpath Layout

```
src/main/resources/
  skills/
    cron/
      SKILL.md
    github/
      SKILL.md
    image-generation/
      SKILL.md
    long-goal/
      SKILL.md
    memory/
      SKILL.md
    clawhub/
      SKILL.md
    skill-creator/
      SKILL.md
    summarize/
      SKILL.md
    tmux/
      SKILL.md
    update-setup/
      SKILL.md
    weather/
      SKILL.md
    my/
      SKILL.md
```

---

## 2. Templates (`src/main/resources/templates/`)

The templates directory contains agent prompt templates, system prompt snippets, and Dream-related templates. These are **Jinja2 templates** in Python rendered with `Jinja2`; in Java they will use **Pebble** (or Thymeleaf).

### 2.1 Complete Template Inventory

| # | Path | Purpose | Rendered By |
|---|------|---------|-------------|
| 1 | `AGENTS.md` | AGENTS.md template synced to workspace | `syncWorkspaceTemplates()` |
| 2 | `HEARTBEAT.md` | Heartbeat/cron task list template | `syncWorkspaceTemplates()` |
| 3 | `SOUL.md` | Bot personality and communication style | `syncWorkspaceTemplates()` |
| 4 | `USER.md` | User profile and preferences | `syncWorkspaceTemplates()` |
| 5 | `agent/identity.md` | Agent identity system prompt template | `PromptTemplates.renderTemplate()` |
| 6 | `agent/platform_policy.md` | Platform policy constraints template | `PromptTemplates.renderTemplate()` |
| 7 | `agent/dream.md` | Dream (memory consolidation) system prompt | `PromptTemplates.renderTemplate()` |
| 8 | `agent/consolidator_archive.md` | Consolidator archive format template | `PromptTemplates.renderTemplate()` |
| 9 | `agent/evaluator.md` | Background task evaluator prompt | `PromptTemplates.renderTemplate()` |
| 10 | `agent/max_iterations_message.md` | Max iterations notice template | `PromptTemplates.renderTemplate()` |
| 11 | `agent/skills_section.md` | Skills section rendering template | `PromptTemplates.renderTemplate()` |
| 12 | `agent/subagent_announce.md` | Subagent announce inject template | `PromptTemplates.renderTemplate()` |
| 13 | `agent/subagent_system.md` | Subagent system prompt template | `PromptTemplates.renderTemplate()` |
| 14 | `agent/tool_contract.md` | Tool contract enforcement template | `PromptTemplates.renderTemplate()` |
| 15 | `agent/_snippets/untrusted_content.md` | Shared include snippet for untrusted content warnings | `include` directive |
| 16 | `memory/MEMORY.md` | Memory file template synced to workspace | `syncWorkspaceTemplates()` |

### 2.2 Classpath Layout

```
src/main/resources/
  templates/
    AGENTS.md
    HEARTBEAT.md
    SOUL.md
    USER.md
    agent/
      identity.md
      platform_policy.md
      dream.md
      consolidator_archive.md
      evaluator.md
      max_iterations_message.md
      skills_section.md
      subagent_announce.md
      subagent_system.md
      tool_contract.md
      _snippets/
        untrusted_content.md
    memory/
      MEMORY.md
```

---

## 3. Java Skill/Template Loading Classes

These loader classes are covered more fully in the `agent-support.md` spec document, but their relationship to skills/templates is summarized here.

### 3.1 `SkillsLoader.java` (~350 lines)

The Python `SkillsLoader` is an **instance-based** class that loads skills from both the workspace and builtin directories, with workspace skills overriding builtin skills by name. The Java port preserves this design exactly.

```java
package com.nanobot.skills;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Loader for agent skills.
 *
 * Skills are Markdown files (SKILL.md) in subdirectories that teach the agent
 * how to use specific tools or perform certain tasks. Skills are loaded from
 * both the workspace and builtin directories, with workspace taking priority.
 *
 * Port of nanobot/agent/skills.py SkillsLoader.
 */
public class SkillsLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillsLoader.class);
    private static final Yaml YAML = new Yaml();

    // Python: _STRIP_SKILL_FRONTMATTER = re.compile(r"^---\s*\r?\n(.*?)\r?\n---\s*\r?\n?", re.DOTALL)
    private static final Pattern STRIP_SKILL_FRONTMATTER = Pattern.compile(
        "^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?",
        Pattern.DOTALL
    );

    // Builtin skills via classpath (Python: BUILTIN_SKILLS_DIR = Path(__file__).parent.parent / "skills")
    private static final String CLASSPATH_SKILLS_PATTERN = "classpath:/skills/*/SKILL.md";

    // ── Instance Fields (Python: self.workspace, self.workspace_skills, self.builtin_skills, self.disabled_skills)
    private final Path workspace;
    private final Path workspaceSkills;     // workspace/skills/
    private final Path builtinSkillsDir;    // nullable — when null, use classpath
    private final Set<String> disabledSkills;

    // ── Records (Python: plain dict entries) ─────────────────

    /** Python: {"name": name, "path": str(skill_file), "source": source} */
    public record SkillEntry(String name, String path, String source) {}

    /** Python: (available: bool, reason: str) */
    public record SkillAvailability(boolean available, String reason) {}

    /** Python: get_skill_requirements() return dict */
    public record SkillRequirements(
        List<String> bins,
        List<String> env,
        List<String> missingBins,
        List<String> missingEnv
    ) {}

    // ── Constructor (Python: __init__(self, workspace, builtin_skills_dir=None, disabled_skills=None)) ─

    public SkillsLoader(Path workspace) {
        this(workspace, null, null);
    }

    public SkillsLoader(Path workspace, Path builtinSkillsDir, Set<String> disabledSkills) {
        this.workspace = workspace;
        this.workspaceSkills = workspace.resolve("skills");
        this.builtinSkillsDir = builtinSkillsDir;
        this.disabledSkills = disabledSkills != null ? disabledSkills : Set.of();
    }

    // ── Directory Scanning (Python: _skill_entries_from_dir) ─

    /**
     * Python: _skill_entries_from_dir(self, base, source, *, skip_names=None)
     *
     * Scans a directory for skill subdirectories containing SKILL.md.
     * Each subdirectory name becomes the skill name.
     */
    private List<SkillEntry> skillEntriesFromDir(Path base, String source, Set<String> skipNames) {
        if (base == null || !Files.isDirectory(base)) {
            return List.of();
        }
        List<SkillEntry> entries = new ArrayList<>();
        try (var dirStream = Files.newDirectoryStream(base, Files::isDirectory)) {
            for (Path skillDir : dirStream) {
                Path skillFile = skillDir.resolve("SKILL.md");
                if (!Files.exists(skillFile)) continue;
                String name = skillDir.getFileName().toString();
                if (skipNames != null && skipNames.contains(name)) continue;
                entries.add(new SkillEntry(name, skillFile.toString(), source));
            }
        } catch (IOException e) {
            log.warn("Failed to scan skill directory: {}", base, e);
        }
        return entries;
    }

    /**
     * Load builtin skill entries from classpath (JAR-safe).
     * Used when builtinSkillsDir is null (the default).
     */
    private List<SkillEntry> builtinSkillEntriesFromClasspath() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<SkillEntry> entries = new ArrayList<>();
        try {
            Resource[] resources = resolver.getResources(CLASSPATH_SKILLS_PATTERN);
            for (Resource res : resources) {
                try {
                    String uri = res.getURI().toString();
                    // URI is like "file:/.../skills/cron/SKILL.md"
                    // Extract skill name from the parent directory
                    String path = uri;
                    int skillsIdx = path.lastIndexOf("/skills/");
                    if (skillsIdx >= 0) {
                        int nameStart = skillsIdx + 8; // "/skills/".length()
                        int nameEnd = path.indexOf('/', nameStart);
                        if (nameEnd > nameStart) {
                            String name = path.substring(nameStart, nameEnd);
                            entries.add(new SkillEntry(name, path, "builtin"));
                        }
                    }
                } catch (IOException e) {
                    log.warn("Failed to resolve skill URI: {}", res, e);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to discover builtin skills on classpath", e);
        }
        return entries;
    }

    // ── listSkills (Python: list_skills) ──────────────────────

    /**
     * Python: list_skills(self, filter_unavailable=True)
     *
     * List all available skills. Workspace skills override builtin by name.
     * When filterUnavailable is true, skills with unmet requirements are excluded.
     */
    public List<SkillEntry> listSkills(boolean filterUnavailable) {
        // Workspace skills first
        List<SkillEntry> skills = skillEntriesFromDir(workspaceSkills, "workspace", null);
        Set<String> workspaceNames = new HashSet<>();
        for (SkillEntry e : skills) workspaceNames.add(e.name());

        // Builtin skills (skip names already in workspace)
        List<SkillEntry> builtinEntries;
        if (builtinSkillsDir != null) {
            builtinEntries = skillEntriesFromDir(builtinSkillsDir, "builtin", workspaceNames);
        } else {
            builtinEntries = builtinSkillEntriesFromClasspath().stream()
                .filter(e -> !workspaceNames.contains(e.name()))
                .toList();
        }
        skills.addAll(builtinEntries);

        // Filter disabled skills
        if (!disabledSkills.isEmpty()) {
            skills.removeIf(s -> disabledSkills.contains(s.name()));
        }

        // Filter by availability
        if (filterUnavailable) {
            skills.removeIf(s -> !checkRequirements(getSkillMeta(s.name())));
        }

        return skills;
    }

    public List<SkillEntry> listSkills() {
        return listSkills(true);
    }

    // ── loadSkill (Python: load_skill) ────────────────────────

    /**
     * Python: load_skill(self, name) -> str | None
     *
     * Load a skill's raw markdown content by name.
     * Checks workspace first, then builtin.
     */
    public String loadSkill(String name) {
        // Workspace first
        Path wsPath = workspaceSkills.resolve(name).resolve("SKILL.md");
        if (Files.exists(wsPath)) {
            try {
                return Files.readString(wsPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.warn("Failed to read workspace skill: {}", wsPath, e);
            }
        }

        // Builtin: try filesystem, then classpath
        if (builtinSkillsDir != null) {
            Path builtinPath = builtinSkillsDir.resolve(name).resolve("SKILL.md");
            if (Files.exists(builtinPath)) {
                try {
                    return Files.readString(builtinPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("Failed to read builtin skill: {}", builtinPath, e);
                }
            }
            return null;
        }

        // Classpath
        return loadSkillFromClasspath(name);
    }

    private String loadSkillFromClasspath(String name) {
        try {
            ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            String pattern = "classpath:/skills/" + name + "/SKILL.md";
            Resource[] resources = resolver.getResources(pattern);
            if (resources.length > 0) {
                return readResource(resources[0]);
            }
        } catch (IOException e) {
            log.warn("Failed to load skill from classpath: {}", name, e);
        }
        return null;
    }

    // ── loadSkillsForContext (Python: load_skills_for_context) ─

    /**
     * Python: load_skills_for_context(self, skill_names)
     *
     * Load specific skills for inclusion in agent context.
     * Frontmatter is stripped from each skill's content.
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

    // ── buildSkillsSummary (Python: build_skills_summary) ─────

    /**
     * Python: build_skills_summary(self, exclude=None)
     *
     * Build a markdown summary of all skills (name, description, path, availability).
     * Used for progressive loading — the agent reads full skill content via read_file when needed.
     */
    public String buildSkillsSummary(Set<String> exclude) {
        List<SkillEntry> allSkills = listSkills(false);
        if (allSkills.isEmpty()) return "";

        List<String> lines = new ArrayList<>();
        for (SkillEntry entry : allSkills) {
            if (exclude != null && exclude.contains(entry.name())) continue;

            Map<String, Object> meta = getSkillMeta(entry.name());
            boolean available = checkRequirements(meta);
            String desc = getSkillDescription(entry.name());

            if (available) {
                lines.add("- **" + entry.name() + "** — " + desc + "  `" + entry.path() + "`");
            } else {
                String missing = getMissingRequirements(meta);
                String suffix = missing.isEmpty() ? " (unavailable)" : " (unavailable: " + missing + ")";
                lines.add("- **" + entry.name() + "** — " + desc + suffix + "  `" + entry.path() + "`");
            }
        }
        return String.join("\n", lines);
    }

    // ── Requirement Checking (Python: _check_requirements, _get_missing_requirements) ─

    /**
     * Python: _check_requirements(self, skill_meta) -> bool
     *
     * Check if skill requirements are met (bins on PATH, env vars set).
     */
    boolean checkRequirements(Map<String, Object> skillMeta) {
        if (skillMeta == null || skillMeta.isEmpty()) return true;

        @SuppressWarnings("unchecked")
        Map<String, Object> requires = (Map<String, Object>) skillMeta.get("requires");
        if (requires == null) return true;

        @SuppressWarnings("unchecked")
        List<String> requiredBins = (List<String>) requires.get("bins");
        if (requiredBins == null) requiredBins = List.of();

        @SuppressWarnings("unchecked")
        List<String> requiredEnvVars = (List<String>) requires.get("env");
        if (requiredEnvVars == null) requiredEnvVars = List.of();

        // Python: all(shutil.which(cmd) for cmd in required_bins)
        for (String cmd : requiredBins) {
            if (!isOnPath(cmd)) return false;
        }
        // Python: all(os.environ.get(var) for var in required_env_vars)
        for (String var : requiredEnvVars) {
            if (System.getenv(var) == null) return false;
        }
        return true;
    }

    /**
     * Python: _get_missing_requirements(self, skill_meta) -> str
     */
    private String getMissingRequirements(Map<String, Object> skillMeta) {
        if (skillMeta == null || skillMeta.isEmpty()) return "";

        @SuppressWarnings("unchecked")
        Map<String, Object> requires = (Map<String, Object>) skillMeta.get("requires");
        if (requires == null) return "";

        @SuppressWarnings("unchecked")
        List<String> requiredBins = (List<String>) requires.get("bins");
        if (requiredBins == null) requiredBins = List.of();

        @SuppressWarnings("unchecked")
        List<String> requiredEnvVars = (List<String>) requires.get("env");
        if (requiredEnvVars == null) requiredEnvVars = List.of();

        List<String> missing = new ArrayList<>();
        for (String cmd : requiredBins) {
            if (!isOnPath(cmd)) missing.add("CLI: " + cmd);
        }
        for (String var : requiredEnvVars) {
            if (System.getenv(var) == null) missing.add("ENV: " + var);
        }
        return String.join(", ", missing);
    }

    /** Python: shutil.which() — check if a binary is on PATH. */
    private static boolean isOnPath(String command) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) return false;
        String name = System.getProperty("os.name", "").toLowerCase().contains("win")
            ? command + ".exe" : command;
        return Stream.of(pathEnv.split(Pattern.quote(System.getProperty("path.separator", ":"))))
            .map(dir -> Path.of(dir).resolve(name))
            .anyMatch(Files::isExecutable);
    }

    // ── Availability (Python: get_skill_availability) ─────────

    /**
     * Python: get_skill_availability(self, name) -> tuple[bool, str]
     */
    public SkillAvailability getSkillAvailability(String name) {
        Map<String, Object> meta = getSkillMeta(name);
        boolean available = checkRequirements(meta);
        String reason = available ? "" : getMissingRequirements(meta);
        return new SkillAvailability(available, reason);
    }

    // ── Requirements (Python: get_skill_requirements) ──────────

    /**
     * Python: get_skill_requirements(self, name) -> dict
     *
     * Returns explicit command/env requirements and currently missing entries.
     */
    public SkillRequirements getSkillRequirements(String name) {
        Map<String, Object> meta = getSkillMeta(name);
        @SuppressWarnings("unchecked")
        Map<String, Object> requires = (meta != null)
            ? (Map<String, Object>) meta.get("requires")
            : null;

        List<String> bins = List.of();
        List<String> env = List.of();
        if (requires != null) {
            @SuppressWarnings("unchecked")
            List<String> rBins = (List<String>) requires.get("bins");
            if (rBins != null) bins = rBins;
            @SuppressWarnings("unchecked")
            List<String> rEnv = (List<String>) requires.get("env");
            if (rEnv != null) env = rEnv;
        }

        List<String> missingBins = bins.stream().filter(b -> !isOnPath(b)).toList();
        List<String> missingEnv = env.stream().filter(v -> System.getenv(v) == null).toList();
        return new SkillRequirements(bins, env, missingBins, missingEnv);
    }

    // ── Description (Python: _get_skill_description) ──────────

    /** Python: _get_skill_description(self, name) -> str */
    private String getSkillDescription(String name) {
        Map<String, Object> meta = getSkillMetadataFull(name);
        if (meta != null && meta.get("description") instanceof String desc && !desc.isBlank()) {
            return desc;
        }
        return name; // fallback
    }

    // ── Frontmatter (Python: _strip_frontmatter) ──────────────

    /**
     * Python: _strip_frontmatter(self, content) -> str
     *
     * Remove YAML frontmatter from markdown content.
     * Uses the same regex as Python (handles CRLF).
     */
    String stripFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) return content;
        Matcher m = STRIP_SKILL_FRONTMATTER.matcher(content);
        if (m.find()) {
            return content.substring(m.end()).strip();
        }
        return content;
    }

    // ── Metadata Parsing (Python: _parse_nanobot_metadata) ────

    /**
     * Python: _parse_nanobot_metadata(self, raw) -> dict
     *
     * Extract nanobot/openclaw metadata from a frontmatter field.
     * ``raw`` may be a Map (already parsed by YAML) or a JSON String.
     * Supports both "nanobot" and "openclaw" top-level keys.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> parseNanobotMetadata(Object raw) {
        Map<String, Object> data;
        if (raw instanceof Map<?, ?> m) {
            data = (Map<String, Object>) m;
        } else if (raw instanceof String s) {
            try {
                data = YAML.load(s);  // SnakeYAML also parses JSON
            } catch (Exception e) {
                return Map.of();
            }
        } else {
            return Map.of();
        }
        if (data == null) return Map.of();

        // Python: payload = data.get("nanobot", data.get("openclaw", {}))
        Object payload = data.get("nanobot");
        if (payload == null) payload = data.get("openclaw");
        return (payload instanceof Map<?, ?> pm) ? (Map<String, Object>) pm : Map.of();
    }

    // ── Skill Meta (Python: _get_skill_meta) ──────────────────

    /**
     * Python: _get_skill_meta(self, name) -> dict
     *
     * Get nanobot metadata for a skill from the "metadata" field in frontmatter.
     */
    Map<String, Object> getSkillMeta(String name) {
        Map<String, Object> rawMeta = getSkillMetadataFull(name);
        if (rawMeta == null) return Map.of();
        Object metadataValue = rawMeta.get("metadata");
        if (metadataValue == null) return Map.of();
        return parseNanobotMetadata(metadataValue);
    }

    // ── Always Skills (Python: get_always_skills) ─────────────

    /**
     * Python: get_always_skills(self) -> list[str]
     *
     * Get skills marked as always=true that meet requirements.
     * Python checks BOTH metadata.nanobot.always AND top-level "always" field.
     */
    public List<String> getAlwaysSkills() {
        List<String> result = new ArrayList<>();
        for (SkillEntry entry : listSkills(true)) {
            Map<String, Object> meta = getSkillMetadataFull(entry.name());
            if (meta == null) continue;

            // Python checks both paths:
            // 1. _parse_nanobot_metadata(meta.get("metadata")).get("always")
            Map<String, Object> nanoMeta = getSkillMeta(entry.name());
            if (Boolean.TRUE.equals(nanoMeta.get("always"))) {
                result.add(entry.name());
                continue;
            }
            // 2. Top-level meta.get("always")
            if (Boolean.TRUE.equals(meta.get("always"))) {
                result.add(entry.name());
            }
        }
        return result;
    }

    // ── Full Metadata (Python: get_skill_metadata) ────────────

    /**
     * Python: get_skill_metadata(self, name) -> dict | None
     *
     * Returns the FULL parsed YAML frontmatter dict (with native types),
     * not just the nanobot metadata subset.
     */
    public Map<String, Object> getSkillMetadataFull(String name) {
        String content = loadSkill(name);
        if (content == null || !content.startsWith("---")) return null;

        Matcher m = STRIP_SKILL_FRONTMATTER.matcher(content);
        if (!m.find()) return null;

        try {
            Object parsed = YAML.load(m.group(1));
            if (parsed instanceof Map<?, ?> pm) {
                // Convert keys to strings (Python: metadata[str(key)] = value)
                Map<String, Object> result = new LinkedHashMap<>();
                for (var entry : ((Map<?, ?>) pm).entrySet()) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return result;
            }
        } catch (Exception e) {
            log.debug("Failed to parse YAML frontmatter for skill: {}", name, e);
        }
        return null;
    }

    // ── I/O Helpers ───────────────────────────────────────────

    private static String readResource(Resource resource) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(
                resource.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buf = new char[8192];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
```

**Key design decisions matching Python:**
1. **Instance-based** — holds workspace path, optional builtin dir, disabled skills set
2. **Workspace-first priority** — workspace skills override builtin skills by name
3. **Frontmatter regex** — uses same pattern as Python (`\r?\n` for CRLF support)
4. **Metadata parsing** — supports both YAML Map and JSON String, with `nanobot`/`openclaw` key fallback
5. **`getAlwaysSkills()`** — checks both `metadata.nanobot.always` AND top-level `always`
6. **`isOnPath()`** — Java equivalent of `shutil.which()` via PATH env var traversal
7. **`listSkills(filterUnavailable)`** — filters by requirement check (Python semantics)
8. **`buildSkillsSummary()`** — progressive loading support with availability annotations
9. **`loadSkillsForContext()`** — strips frontmatter, formats with `### Skill: name` headers
10. **`getSkillMetadataFull()`** — returns full YAML dict (Python returns native types from `yaml.safe_load`)

### 3.2 `PromptTemplates.java` (~60 lines)

Already described in the **15-utils.md** spec. Key method (updated with `strip` parameter matching Python's `render_template(name, *, strip=False)`):

```java
/** Python: render_template(name, *, strip=False, **kwargs) */
public static String renderTemplate(String name, boolean strip, Map<String, Object> context) {
    PebbleTemplate compiledTemplate = pebbleEngine.getTemplate(name);
    StringWriter writer = new StringWriter();
    compiledTemplate.evaluate(writer, context);
    String text = writer.toString();
    return strip ? text.stripTrailing() : text;  // Python: text.rstrip() when strip=True
}

public static String renderTemplate(String name, Map<String, Object> context) {
    return renderTemplate(name, false, context);
}
```

### 3.3 `SkillsController.java` (~60 lines)

Port of `nanobot/webui/skills_api.py` — provides REST endpoints for the WebUI to list skills and get skill details **without leaking local filesystem paths**.

```java
package com.nanobot.webui;

import com.nanobot.skills.SkillsLoader;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/skills")
public class SkillsController {

    private final SkillsLoader loader;

    public SkillsController(SkillsLoader loader) {
        this.loader = loader;
    }

    /**
     * Python: webui_skills_payload(workspace_path, disabled_skills=...)
     *
     * Return agent skills without leaking local filesystem paths.
     * Skills are sorted: workspace skills first, then by name.
     */
    @GetMapping
    public Map<String, Object> skillsPayload(
        @RequestParam(defaultValue = "") Set<String> disabledSkills
    ) {
        List<Map<String, Object>> skills = new ArrayList<>();
        for (SkillsLoader.SkillEntry entry : loader.listSkills(false)) {
            skills.add(skillPayload(entry));
        }
        // Python: sorted by (source != "workspace", name)
        skills.sort(Comparator
            .comparing((Map<String, Object> s) -> !"workspace".equals(s.get("source")))
            .thenComparing(s -> (String) s.get("name")));
        return Map.of("skills", skills);
    }

    /**
     * Python: webui_skill_detail_payload(workspace_path, name, disabled_skills=...)
     *
     * Return a single skill's safe detail payload with requirements and raw markdown.
     */
    @GetMapping("/{name}")
    public Map<String, Object> skillDetailPayload(@PathVariable String name) {
        SkillsLoader.SkillEntry entry = loader.listSkills(false).stream()
            .filter(e -> e.name().equals(name))
            .findFirst().orElse(null);
        if (entry == null) return null;

        Map<String, Object> payload = new LinkedHashMap<>(skillPayload(entry));
        payload.put("requirements", loader.getSkillRequirements(name));
        payload.put("raw_markdown", loader.loadSkill(name) != null ? loader.loadSkill(name) : "");
        return payload;
    }

    /**
     * Python: _skill_payload(loader, entry) -> dict
     *
     * Safe payload: name, description, source, available, unavailable_reason.
     * No local filesystem paths exposed.
     */
    private Map<String, Object> skillPayload(SkillsLoader.SkillEntry entry) {
        String name = entry.name();
        Map<String, Object> metadata = loader.getSkillMetadataFull(name);
        SkillsLoader.SkillAvailability avail = loader.getSkillAvailability(name);
        return Map.of(
            "name", name,
            "description", skillDescription(metadata, name),
            "source", entry.source(),
            "available", avail.available(),
            "unavailable_reason", avail.reason()
        );
    }

    /** Python: _description(metadata, fallback) */
    private static String skillDescription(Map<String, Object> metadata, String fallback) {
        if (metadata == null) return fallback;
        Object value = metadata.get("description");
        return (value instanceof String s && !s.isBlank()) ? s.strip() : fallback;
    }
}
```

---

## 4. Pebble Template Engine Configuration

### 4.1 Maven Dependency

```xml
<dependency>
    <groupId>io.pebbletemplates</groupId>
    <artifactId>pebble-spring-boot-starter</artifactId>
    <version>3.2.2</version>
</dependency>
```

### 4.2 Template Syntax Migration (Jinja2 to Pebble)

Both Jinja2 and Pebble use `{{ variable }}` and `{% block %}` syntax, making migration straightforward:

| Jinja2 | Pebble | Notes |
|--------|--------|-------|
| `{{ var }}` | `{{ var }}` | Identical |
| `{% if cond %}` | `{% if cond %}` | Identical |
| `{% for item in list %}` | `{% for item in list %}` | Identical |
| `{% include 'path' %}` | `{% include 'path' %}` | Identical |
| `{{ var \| default('x') }}` | `{{ var \| default('x') }}` | Identical |
| `{# comment #}` | `{# comment #}` | Identical |
| `{% set x = 1 %}` | `{% set x = 1 %}` | Identical |

Most templates require **zero syntax changes** for the migration.

### 4.3 `PebbleConfig.java`

```java
package com.nanobot.config;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.loader.ClasspathLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PebbleConfig {

    @Bean
    public PebbleEngine pebbleEngine() {
        ClasspathLoader loader = new ClasspathLoader();
        loader.setPrefix("templates/");
        loader.setSuffix(""); // templates already have .md extension

        return new PebbleEngine.Builder()
            .loader(loader)
            .autoEscaping(false)     // Python: autoescape=False — plain-text prompts
            .strictVariables(false)  // Allow missing variables in templates
            .trimBlocks(true)        // Python: trim_blocks=True
            .lstripBlocks(true)      // Python: lstrip_blocks=True
            .cacheActive(true)       // Cache compiled templates
            .build();
    }
}
```

---

## 5. Workspace Sync — Template File Copy

During initialization, `syncWorkspaceTemplates()` copies bundled template files from the classpath to the workspace directory, creating missing files without overwriting user modifications:

```java
/**
 * Sync bundled templates to workspace.
 * Creates missing files without overwriting existing user files.
 * Returns list of created relative paths.
 */
public static List<String> syncWorkspaceTemplates(Path workspace, boolean silent) {
    List<String> added = new ArrayList<>();

    // Python: for item in tpl.iterdir():
    //   if item.name.endswith(".md") and not item.name.startswith("."):
    //     _write(item, workspace / item.name)
    // Dynamically discover root-level .md templates (not recursive — only root level)
    ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
    try {
        Resource[] rootMdFiles = resolver.getResources("classpath:/templates/*.md");
        for (Resource res : rootMdFiles) {
            String filename = res.getFilename();  // e.g. "AGENTS.md"
            if (filename == null || filename.startsWith(".")) continue;
            Path dest = workspace.resolve(filename);
            if (!Files.exists(dest)) {
                try {
                    String content = readResource(res);
                    Files.createDirectories(dest.getParent());
                    Files.writeString(dest, content, StandardCharsets.UTF_8);
                    added.add(filename);
                } catch (IOException e) {
                    log.warn("Failed to sync template: {}", filename, e);
                }
            }
        }
    } catch (IOException e) {
        log.warn("Failed to discover root templates on classpath", e);
    }

    // Memory directory
    try {
        syncIfMissing(workspace.resolve("memory/MEMORY.md"),
            "templates/memory/MEMORY.md", added, workspace);
        // Create empty history.jsonl if missing
        Path historyPath = workspace.resolve("memory/history.jsonl");
        if (!Files.exists(historyPath)) {
            Files.createDirectories(historyPath.getParent());
            Files.writeString(historyPath, "", StandardCharsets.UTF_8);
        }
    } catch (IOException e) {
        log.warn("Failed to sync memory templates", e);
    }

    // Ensure skills directory exists
    try {
        Files.createDirectories(workspace.resolve("skills"));
    } catch (IOException e) {
        log.warn("Failed to create skills directory", e);
    }

    // Initialize GitStore for memory version control
    try {
        GitStore gs = new GitStore(workspace,
            List.of("SOUL.md", "USER.md", "memory/MEMORY.md"));
        gs.init();
    } catch (IOException e) {
        log.error("Failed to initialize git store for {}", workspace, e);
    }

    if (!added.isEmpty() && !silent) {
        for (String name : added) {
            System.out.println("  Created " + name);
        }
    }

    return added;
}
```

---

## 6. File Layout Summary

```
src/main/resources/
  skills/
    cron/SKILL.md
    github/SKILL.md
    image-generation/SKILL.md
    long-goal/SKILL.md
    memory/SKILL.md
    clawhub/SKILL.md
    skill-creator/SKILL.md
    summarize/SKILL.md
    tmux/SKILL.md
    update-setup/SKILL.md
    weather/SKILL.md
    my/SKILL.md
  templates/
    AGENTS.md
    HEARTBEAT.md
    SOUL.md
    USER.md
    agent/
      identity.md
      platform_policy.md
      dream.md
      consolidator_archive.md
      evaluator.md
      max_iterations_message.md
      skills_section.md
      subagent_announce.md
      subagent_system.md
      tool_contract.md
      _snippets/
        untrusted_content.md
    memory/
      MEMORY.md

src/main/java/com/nanobot/skills/
  SkillsLoader.java       (~350 lines)
  package-info.java

src/main/java/com/nanobot/webui/
  SkillsController.java   (~60 lines)

src/main/java/com/nanobot/utils/
  PromptTemplates.java    (~60 lines, called from 15-utils.md)
```

---

## 7. Build Integration

In `pom.xml`, ensure the resources are included:

```xml
<build>
    <resources>
        <resource>
            <directory>src/main/resources</directory>
            <includes>
                <include>skills/**/*.md</include>
                <include>templates/**/*.md</include>
            </includes>
        </resource>
    </resources>
</build>
```

The markdown files are plain text resources that get copied into the JAR during the `package` phase and are readable via `ClasspathLoader` at runtime.

---

## 8. Notes

1. **No code changes to .md files:** The SKILL.md and template .md files are copied **verbatim** from the Python project. No syntax changes are needed since they are plain natural language markdown consumed by the LLM, not Python code. Template files use Jinja2→Pebble which has identical syntax (`{{ }}`, `{% %}`).

2. **`always: true` skill:** Only the `memory` skill has `always: true` (at the top level of frontmatter). Python's `get_always_skills()` checks BOTH `metadata.nanobot.always` (via `_parse_nanobot_metadata`) AND top-level `always`. The Java `getAlwaysSkills()` implements both paths exactly.

3. **Dream-managed files:** `SOUL.md`, `USER.md`, and `memory/MEMORY.md` are managed by the Dream memory consolidation process. The agent is instructed not to edit them directly. The GitStore tracks changes to these files.

4. **YAML parsing:** The `SkillsLoader` uses SnakeYAML (already included with Spring Boot) to parse frontmatter. Python's `yaml.safe_load` returns native types (int, bool, list); SnakeYAML behaves identically.

5. **Instance-based vs static:** Python's `SkillsLoader` is instance-based (holds workspace, builtin dir, disabled skills). The Java version preserves this design — `ContextBuilder` creates a `SkillsLoader` instance on construction, just like Python.

6. **Workspace-first priority:** When both workspace and builtin have a skill with the same name, workspace wins. Python scans workspace first then skips those names when scanning builtin. Java replicates this logic exactly in `listSkills()`.

7. **Metadata format flexibility:** Python's `_parse_nanobot_metadata()` handles `metadata` as either a YAML dict or JSON string, and checks for both `nanobot` and `openclaw` top-level keys. Java's `parseNanobotMetadata()` handles the same cases.

8. **Frontmatter CRLF handling:** Python uses `r"^---\s*\r?\n(.*?)\r?\n---\s*\r?\n?"` regex. Java uses the identical pattern with `Pattern.DOTALL` for cross-platform line ending support.

9. **`isOnPath()`:** Java's equivalent of Python's `shutil.which()` walks the system PATH, checking each directory for an executable file with the given name (appending `.exe` on Windows).

---
## 9. Verification

### 9.1 Source Mapping

| Python File (nanobot/) | Lines | Java Class | Lines | Notes |
|------------------------|-------|------------|-------|-------|
| `agent/skills.py` | 261 | `SkillsLoader.java` | ~350 | Instance-based, 16 methods |
| `webui/skills_api.py` | 62 | `SkillsController.java` | ~60 | REST controller, no path leaks |
| `utils/prompt_templates.py` | 36 | `PromptTemplates.java` | ~60 | Pebble, `strip` param added |
| `utils/helpers.py` (sync fn) | ~80 | `Helpers.syncWorkspaceTemplates()` | ~60 | Dynamic classpath discovery |
| — (in build.gradle) | — | `PebbleConfig.java` | ~25 | trim/lstrip blocks config |
| 18 markdown files | ~1850 | 18 markdown files | ~1850 | Copied verbatim |
| **Total** | **~2289** | | **~2405** | |

### 9.2 Method-Level Verification

| # | Python Method (skills.py:line) | Java Method | Match |
|---|-------------------------------|-------------|-------|
| 1 | `__init__(workspace, builtin_skills_dir, disabled_skills)` :29 | `SkillsLoader(Path, Path, Set)` constructor | ✅ |
| 2 | `_skill_entries_from_dir(base, source, *, skip_names)` :35 | `skillEntriesFromDir(Path, String, Set)` | ✅ |
| 3 | `list_skills(filter_unavailable=True)` :51 | `listSkills(boolean)` | ✅ |
| 4 | Workspace-first priority (workspace before builtin) :62–66 | Workspace-first then builtin in `listSkills()` | ✅ |
| 5 | Builtin skips workspace names :65 | `skipNames`/`workspaceNames` filter | ✅ |
| 6 | `disabled_skills` filter :68–69 | `disabledSkills` filter | ✅ |
| 7 | `filter_unavailable` → `_check_requirements` :71–72 | `filterUnavailable` → `checkRequirements()` | ✅ |
| 8 | `load_skill(name)` :75 | `loadSkill(String)` | ✅ |
| 9 | Workspace then builtin priority :85–91 | Workspace → filesystem builtin → classpath | ✅ |
| 10 | `load_skills_for_context(skill_names)` :94 | `loadSkillsForContext(List)` | ✅ |
| 11 | Frontmatter stripped per skill :104–108 | `stripFrontmatter()` per skill | ✅ |
| 12 | `### Skill: {name}` format :105 | Same header format | ✅ |
| 13 | `---` separator between skills :109 | Same separator | ✅ |
| 14 | `build_skills_summary(exclude)` :111 | `buildSkillsSummary(Set)` | ✅ |
| 15 | Available skill line format :137 | Same format (bold name, desc, path) | ✅ |
| 16 | Unavailable skill with missing reason :139–141 | Same format with `getMissingRequirements()` | ✅ |
| 17 | `_get_missing_requirements(skill_meta)` :144 | `getMissingRequirements(Map)` | ✅ |
| 18 | CLI prefix for missing bins :150 | `"CLI: " + cmd` | ✅ |
| 19 | ENV prefix for missing env vars :151 | `"ENV: " + var` | ✅ |
| 20 | `get_skill_availability(name)` :154 | `getSkillAvailability(String)` → `SkillAvailability` | ✅ |
| 21 | `get_skill_requirements(name)` :160 | `getSkillRequirements(String)` → `SkillRequirements` | ✅ |
| 22 | Returns bins, env, missing_bins, missing_env :163–169 | All 4 fields in record | ✅ |
| 23 | `_get_skill_description(name)` :172 | `getSkillDescription(String)` | ✅ |
| 24 | Falls back to skill name :177 | Same fallback | ✅ |
| 25 | `_strip_frontmatter(content)` :179 | `stripFrontmatter(String)` | ✅ |
| 26 | `STRIP_SKILL_FRONTMATTER` regex with `\r?\n` :15–18 | Same regex `Pattern.DOTALL` | ✅ |
| 27 | `_parse_nanobot_metadata(raw)` :188 | `parseNanobotMetadata(Object)` | ✅ |
| 28 | Dict input :193 | `instanceof Map` | ✅ |
| 29 | JSON string input :195–199 | `YAML.load(String)` for JSON | ✅ |
| 30 | `nanobot` / `openclaw` fallback :204 | Same fallback order | ✅ |
| 31 | `_check_requirements(skill_meta)` :207 | `checkRequirements(Map)` | ✅ |
| 32 | `shutil.which()` for bins :212 | `isOnPath()` PATH walk | ✅ |
| 33 | `os.environ.get()` for env vars :213 | `System.getenv()` | ✅ |
| 34 | `_get_skill_meta(name)` :216 | `getSkillMeta(String)` | ✅ |
| 35 | `get_always_skills()` :221 | `getAlwaysSkills()` | ✅ |
| 36 | Checks `metadata.nanobot.always` :228 | Same path via `parseNanobotMetadata` | ✅ |
| 37 | Checks top-level `meta.get("always")` :229 | Same fallback | ✅ |
| 38 | Filters by availability :225 | Same filter via `listSkills(true)` | ✅ |
| 39 | `get_skill_metadata(name)` :233 | `getSkillMetadataFull(String)` | ✅ |
| 40 | Returns full parsed YAML dict :249–260 | Returns `Map<String,Object>` with native types | ✅ |

| # | Python (skills_api.py:line) | Java Method (SkillsController) | Match |
|---|----------------------------|-------------------------------|-------|
| 41 | `webui_skills_payload(workspace_path, *, disabled_skills)` :11 | `skillsPayload(Set)` GET /api/skills | ✅ |
| 42 | Sorted: source!=workspace, then name :18–20 | Same comparator | ✅ |
| 43 | `webui_skill_detail_payload(workspace_path, name, ...)` :25 | `skillDetailPayload(String)` GET /api/skills/{name} | ✅ |
| 44 | requirements field :39 | `getSkillRequirements(name)` | ✅ |
| 45 | raw_markdown field :40 | `loadSkill(name)` | ✅ |
| 46 | `_skill_payload(loader, entry)` :44 | `skillPayload(SkillEntry)` | ✅ |
| 47 | No local filesystem paths leaked | No path field in response | ✅ |
| 48 | `_description(metadata, fallback)` :57 | `skillDescription(Map, String)` | ✅ |

| # | Python (prompt_templates.py:line) | Java Method (PromptTemplates) | Match |
|---|----------------------------------|-------------------------------|-------|
| 49 | `_TEMPLATES_ROOT` :14 | `ClasspathLoader` prefix "templates/" | ✅ |
| 50 | `autoescape=False` :22 | `autoEscaping(false)` | ✅ |
| 51 | `trim_blocks=True` :23 | `trimBlocks(true)` | ✅ |
| 52 | `lstrip_blocks=True` :24 | `lstripBlocks(true)` | ✅ |
| 53 | `render_template(name, *, strip=False, **kwargs)` :28 | `renderTemplate(String, boolean, Map)` | ✅ |
| 54 | `strip=True` → rstrip :34–35 | `strip` parameter → `stripTrailing()` | ✅ |

| # | Python (helpers.py:line) | Java Method (Helpers) | Match |
|---|-------------------------|-----------------------|-------|
| 55 | `sync_workspace_templates(workspace, silent)` :578 | `syncWorkspaceTemplates(Path, boolean)` | ✅ |
| 56 | Root .md file iteration via `iterdir()` :599–601 | `classpath:/templates/*.md` dynamic discovery | ✅ |
| 57 | Skips `.` prefix files :600 | `filename.startsWith(".")` check | ✅ |
| 58 | Creates `memory/MEMORY.md` :602 | Same explicit sync | ✅ |
| 59 | Creates empty `memory/history.jsonl` :603 | Same empty file creation | ✅ |
| 60 | Creates `skills/` directory :604 | Same `createDirectories` | ✅ |
| 61 | GitStore init :613–624 | Same GitStore init with tracked files | ✅ |
| 62 | Console output with `[dim]` styling :609–610 | `System.out.println("  Created " + name)` | ✅ |
| 63 | `load_bundled_template(template_name)` :631 | Already in Helpers.java (15-utils.md) | ✅ |

### 9.3 Skill Count Verification

| Item | Python Count | Java Count | Match |
|------|-------------|------------|-------|
| Builtin skills (SKILL.md files) | 12 | 12 | ✅ |
| Root templates (.md at root) | 4 | 4 | ✅ |
| Agent templates (agent/*.md) | 7 | 7 | ✅ |
| Snippets (_snippets/*.md) | 1 | 1 | ✅ |
| Memory templates (memory/*.md) | 1 | 1 | ✅ |
| **Total markdown files** | **25** | **25** | ✅ |

### 9.4 Build Verification

```bash
# Skills are on classpath
jar tf target/nanobot-*.jar | grep 'skills/.*/SKILL.md' | wc -l
# Expected: 12

# Templates are on classpath
jar tf target/nanobot-*.jar | grep 'templates/.*\.md' | wc -l
# Expected: 13 (4 root + 7 agent + 1 snippet + 1 memory)

# Pebble template rendering works
curl -s http://localhost:8080/actuator/health  # Spring Boot health check

# Skills API returns correct JSON
curl -s http://localhost:8080/api/skills | jq '.skills | length'
# Expected: 12

# Memory skill has always=true
curl -s http://localhost:8080/api/skills/memory | jq '.available'
# Expected: true
```

### 9.5 Remaining Notes

1. **`trim_blocks` / `lstrip_blocks` in Pebble:** Pebble supports these settings natively. The Python Jinja2 environment enables both; Pebble defaults may differ but the explicit builder settings ensure parity.

2. **Classpath vs filesystem for builtin skills:** Python uses filesystem paths (runs from source tree). Java uses classpath scanning (runs from JAR). The Java constructor accepts an optional `builtinSkillsDir` Path for filesystem-based loading (useful in development/IDE runs). When null (default), classpath scanning is used.

3. **ContextBuilder integration:** The Java `ContextBuilder` (see `18-remaining.md` or agent-support spec) creates a `SkillsLoader` instance on construction, identical to Python's `self.skills = SkillsLoader(workspace, ...)`. The `buildSystemPrompt()` method uses `getAlwaysSkills()`, `loadSkillsForContext()`, and `buildSkillsSummary()` in the same way as Python.
