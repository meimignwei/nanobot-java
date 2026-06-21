# Package 17: Skills + Templates (`src/main/resources/skills/` and `src/main/resources/templates/`)

## Overview

The `nanobot/skills/` and `nanobot/templates/` directories contain **static Markdown files** that are loaded into the agent's system prompt context at runtime. They are not executable code -- they are LLM context resources. In the Java port, these move to `src/main/resources/skills/` and `src/main/resources/templates/` on the classpath.

**Original Python files:** 18 markdown files + 1 README.
**Estimated Java code:** ~150 lines (just the loader -- the markdown files are copied verbatim).

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

### 3.1 `SkillsLoader.java` (~80 lines)

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
import java.util.*;

/**
 * Loads skill definitions from classpath resources.
 *
 * Skills are static Markdown files under src/main/resources/skills/.
 * Each skill is a subdirectory containing a SKILL.md file with YAML frontmatter.
 */
public class SkillsLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillsLoader.class);

    private static final String SKILLS_ROOT = "classpath:/skills/*/SKILL.md";
    private static final Yaml YAML = new Yaml();

    // ── Skill Definition Record ───────────────────────────────

    public record SkillDef(
        String name,
        String description,
        boolean always,        // loaded into every context
        String emoji,           // optional display emoji
        List<String> requiresBins,  // required binaries
        String content          // full markdown body (without frontmatter)
    ) {}

    // ── Loading ────────────────────────────────────────────────

    /**
     * Load all skills from the classpath.
     * Returns an unmodifiable list ordered by skill name.
     */
    public static List<SkillDef> loadAll() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<SkillDef> skills = new ArrayList<>();

        try {
            Resource[] resources = resolver.getResources(SKILLS_ROOT);
            for (Resource resource : resources) {
                try {
                    String raw = readResource(resource);
                    SkillDef skill = parseSkillMarkdown(raw, resource);
                    if (skill != null) {
                        skills.add(skill);
                    }
                } catch (IOException e) {
                    log.warn("Failed to read skill resource: {}", resource, e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to discover skill resources", e);
        }

        skills.sort(Comparator.comparing(SkillDef::name));
        return Collections.unmodifiableList(skills);
    }

    /**
     * Load only the "always" skills (loaded into every context).
     */
    public static List<SkillDef> loadAlways() {
        return loadAll().stream()
            .filter(SkillDef::always)
            .toList();
    }

    /**
     * Load a specific skill by name.
     */
    public static Optional<SkillDef> loadByName(String name) {
        return loadAll().stream()
            .filter(s -> s.name().equals(name))
            .findFirst();
    }

    // ── Parsing ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    static SkillDef parseSkillMarkdown(String raw, Resource resource) {
        // Parse YAML frontmatter between --- delimiters
        if (!raw.startsWith("---")) {
            log.warn("Skill {} has no frontmatter", resource);
            return null;
        }

        int endFrontmatter = raw.indexOf("---", 3);
        if (endFrontmatter < 0) {
            log.warn("Skill {} has unclosed frontmatter", resource);
            return null;
        }

        String yamlStr = raw.substring(3, endFrontmatter).strip();
        String content = raw.substring(endFrontmatter + 3).strip();

        Map<String, Object> frontmatter;
        try {
            frontmatter = YAML.load(yamlStr);
            if (frontmatter == null) frontmatter = Map.of();
        } catch (Exception e) {
            log.warn("Skill {} has invalid YAML frontmatter: {}", resource, e.getMessage());
            return null;
        }

        String name = Objects.toString(frontmatter.get("name"), "");
        String description = Objects.toString(frontmatter.get("description"), "");
        boolean always = Boolean.TRUE.equals(frontmatter.get("always"));

        // Extract metadata (emoji, requires.bins)
        String emoji = null;
        List<String> requiresBins = List.of();

        Object metadata = frontmatter.get("metadata");
        if (metadata instanceof Map<?, ?> meta) {
            Object nanobotMeta = meta.get("nanobot");
            if (nanobotMeta instanceof Map<?, ?> nm) {
                emoji = Objects.toString(nm.get("emoji"), null);
                Object requires = nm.get("requires");
                if (requires instanceof Map<?, ?> req) {
                    Object bins = req.get("bins");
                    if (bins instanceof List<?> binList) {
                        requiresBins = binList.stream()
                            .map(Object::toString)
                            .toList();
                    }
                }
            }
        }

        return new SkillDef(name, description, always, emoji, requiresBins, content);
    }

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

    // ── Rendering for Prompt Context ───────────────────────────

    /**
     * Render all loaded skills as a combined prompt section.
     */
    public static String renderSkillsPrompt(List<SkillDef> skills) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Available Skills\n\n");
        for (SkillDef skill : skills) {
            sb.append("### ").append(skill.name()).append("\n");
            sb.append(skill.content()).append("\n\n");
        }
        return sb.toString();
    }
}
```

### 3.2 `PromptTemplates.java` (~60 lines)

Already described in the **15-utils.md** spec. Key method:

```java
public static String renderTemplate(String name, Map<String, Object> context) {
    // Uses Pebble to render templates from classpath:/templates/
    PebbleTemplate compiledTemplate = pebbleEngine.getTemplate(name);
    StringWriter writer = new StringWriter();
    compiledTemplate.evaluate(writer, context);
    return writer.toString();
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
            .autoEscaping(false)     // Plain-text prompts: do not HTML-escape
            .strictVariables(false)  // Allow missing variables in templates
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

    // Root-level templates
    String[] rootTemplates = {"SOUL.md", "USER.md", "HEARTBEAT.md", "AGENTS.md"};
    for (String name : rootTemplates) {
        Path dest = workspace.resolve(name);
        if (!Files.exists(dest)) {
            try {
                String content = readClasspathResource("templates/" + name);
                if (content != null) {
                    Files.createDirectories(dest.getParent());
                    Files.writeString(dest, content, StandardCharsets.UTF_8);
                    added.add(name);
                }
            } catch (IOException e) {
                log.warn("Failed to sync template: {}", name, e);
            }
        }
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
  SkillsLoader.java       (~80 lines)
  package-info.java

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

1. **No code changes to .md files:** The SKILL.md and template .md files are copied **verbatim** from the Python project. No syntax changes are needed since they are plain natural language markdown consumed by the LLM, not Python code.

2. **`always: true` skill:** Only the `memory` skill has `always: true`. It is loaded into every agent context regardless of tool availability. All other skills are only loaded when their corresponding tools are available.

3. **Dream-managed files:** `SOUL.md`, `USER.md`, and `memory/MEMORY.md` are managed by the Dream memory consolidation process. The agent is instructed not to edit them directly. The GitStore tracks changes to these files.

4. **YAML parsing:** The `SkillsLoader` uses SnakeYAML (already included with Spring Boot) to parse frontmatter. If SnakeYAML is not already on the classpath, add:
   ```xml
   <dependency>
       <groupId>org.yaml</groupId>
       <artifactId>snakeyaml</artifactId>
   </dependency>
   ```
   (Spring Boot includes this transitively.)
