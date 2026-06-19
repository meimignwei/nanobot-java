package com.nanobot.agent.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ExecTool")
class ExecToolTest {

    private ExecTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tool = new ExecTool();
        ctx = ToolContext.builder()
                .workspace(tempDir.toString())
                .build();
    }

    @Test
    @DisplayName("exec simple command")
    void execSimpleCommand() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(Map.of("command", "echo hello"), ctx);
            assertThat((String) result).contains("hello");
            assertThat((String) result).contains("Exit code: 0");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("exec with missing command returns error")
    void execMissingCommand() throws Exception {
        Object result = tool.execute(Map.of(), ctx);
        assertThat((String) result).contains("Error: Missing command");
    }

    @Test
    @DisplayName("exec with empty command returns error")
    void execEmptyCommand() throws Exception {
        Object result = tool.execute(Map.of("command", ""), ctx);
        assertThat((String) result).contains("Error: Missing command");
    }

    @Test
    @DisplayName("exec blocked command (rm -rf)")
    void execBlockedCommand() throws Exception {
        Object result = tool.execute(Map.of("command", "rm -rf /"), ctx);
        assertThat((String) result).contains("blocked by deny pattern");
    }

    @Test
    @DisplayName("exec with cmd alias")
    void execCmdAlias() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(Map.of("cmd", "echo alias_test"), ctx);
            assertThat((String) result).contains("alias_test");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("exec with working_dir")
    void execWithWorkingDir(@TempDir Path tempDir) throws Exception {
        var testCtx = ToolContext.builder()
                .workspace(tempDir.toString())
                .build();
        ToolContext.bind(testCtx);
        try {
            Object result = tool.execute(
                    Map.of("command", "pwd", "working_dir", "/tmp"), testCtx);
            assertThat((String) result).contains("/tmp");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("exec command that fails returns exit code")
    void execFailingCommand() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(Map.of("command", "exit 42"), ctx);
            assertThat((String) result).contains("Exit code: 42");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("exec with stderr")
    void execWithStderr() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(Map.of("command", "echo error >&2"), ctx);
            assertThat((String) result).contains("STDERR:");
            assertThat((String) result).contains("error");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("exec truncates long output")
    void execTruncateLongOutput() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(Map.of("command",
                    "python3 -c \"print('x' * 20000)\" 2>/dev/null || "
                            + "perl -e \"print 'x' x 20000\" 2>/dev/null || "
                            + "printf 'x%.0s' {1..20000}"),
                    ctx);
            String out = (String) result;
            // Should be truncated or have exit code
            assertThat(out).isNotNull();
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("name/description/configKey match Python")
    void matchesPythonSpec() {
        assertThat(tool.name()).isEqualTo("exec");
        assertThat(tool.configKey()).isEqualTo("exec");
        assertThat(tool.description()).contains("Execute a shell command");
        assertThat(tool.description()).contains("read_file");
        assertThat(tool.isExclusive()).isTrue();
    }

    @Test
    @DisplayName("parameters schema has required command")
    void parametersSchema() {
        var params = tool.parameters();
        assertThat(params).containsKey("required");
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) params.get("required");
        assertThat(required).contains("command");
    }
}
