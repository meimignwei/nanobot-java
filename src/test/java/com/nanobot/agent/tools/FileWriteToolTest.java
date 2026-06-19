package com.nanobot.agent.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FileWriteTool")
class FileWriteToolTest {

    private FileWriteTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tool = new FileWriteTool(tempDir.toString());
        ctx = ToolContext.builder()
                .workspace(tempDir.toString())
                .build();
    }

    @Test
    @DisplayName("write new file in workspace")
    void writeNewFile() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "output.txt", "content", "Hello, World!"), ctx);
            assertThat((String) result).contains("Successfully wrote");
            assertThat((String) result).contains("13 characters");
        } finally {
            ToolContext.unbind();
        }

        // Verify content
        Path written = Path.of(ctx.workspace()).resolve("output.txt");
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo("Hello, World!");
    }

    @Test
    @DisplayName("overwrite existing file")
    void overwriteExistingFile() throws Exception {
        Path file = Path.of(ctx.workspace()).resolve("existing.txt");
        Files.writeString(file, "old content");

        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "existing.txt", "content", "new content"), ctx);
            assertThat((String) result).contains("Successfully wrote");
        } finally {
            ToolContext.unbind();
        }

        assertThat(Files.readString(file)).isEqualTo("new content");
    }

    @Test
    @DisplayName("create parent directories")
    void createParentDirs() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "a/b/c/deep.txt", "content", "deep"), ctx);
            assertThat((String) result).contains("Successfully wrote");
        } finally {
            ToolContext.unbind();
        }

        Path written = Path.of(ctx.workspace()).resolve("a/b/c/deep.txt");
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo("deep");
    }

    @Test
    @DisplayName("missing path returns error")
    void missingPath() throws Exception {
        Object result = tool.execute(
                java.util.Map.of("content", "stuff"), ctx);
        assertThat((String) result).contains("Unknown path");
    }

    @Test
    @DisplayName("missing content returns error")
    void missingContent() throws Exception {
        Object result = tool.execute(
                java.util.Map.of("path", "file.txt"), ctx);
        assertThat((String) result).contains("Unknown content");
    }

    @Test
    @DisplayName("path outside workspace rejected")
    void pathOutsideWorkspace() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "/etc/hacked", "content", "evil"), ctx);
            assertThat((String) result).contains("outside the workspace");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("name matches Python")
    void nameMatchesPython() {
        assertThat(tool.name()).isEqualTo("write_file");
        assertThat(tool.description()).contains("Create a new file");
        assertThat(tool.description()).contains("apply_patch");
    }
}
