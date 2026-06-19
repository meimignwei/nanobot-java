package com.nanobot.agent.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FileReadTool")
class FileReadToolTest {

    private FileReadTool tool;
    private ToolContext ctx;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tool = new FileReadTool(tempDir.toString());
        ctx = ToolContext.builder()
                .workspace(tempDir.toString())
                .build();
    }

    @Test
    @DisplayName("read existing file in workspace")
    void readExistingFile() throws Exception {
        Path file = Path.of(ctx.workspace()).resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\n");

        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "test.txt"), ctx);
            String output = (String) result;
            assertThat(output).contains("1| line1");
            assertThat(output).contains("2| line2");
            assertThat(output).contains("3| line3");
            assertThat(output).contains("End of file");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("read with offset and limit")
    void readWithOffsetLimit() throws Exception {
        Path file = Path.of(ctx.workspace()).resolve("lines.txt");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("line ").append(i).append("\n");
        }
        Files.writeString(file, sb.toString());

        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "lines.txt", "offset", 5, "limit", 3), ctx);
            String output = (String) result;
            assertThat(output).contains("5| line 5");
            assertThat(output).contains("7| line 7");
            assertThat(output).doesNotContain("4| line 4");
            assertThat(output).doesNotContain("8| line 8");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("read missing file returns error")
    void readMissingFile() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "nonexistent.txt"), ctx);
            assertThat((String) result).contains("Error: File not found");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("read empty file")
    void readEmptyFile() throws Exception {
        Path file = Path.of(ctx.workspace()).resolve("empty.txt");
        Files.writeString(file, "");

        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "empty.txt"), ctx);
            assertThat((String) result).contains("Empty file");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("offset beyond end of file")
    void offsetBeyondEnd() throws Exception {
        Path file = Path.of(ctx.workspace()).resolve("short.txt");
        Files.writeString(file, "only\n2\nlines\n");

        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "short.txt", "offset", 10), ctx);
            assertThat((String) result).contains("beyond end of file");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("blocked device path")
    void blockedDevice() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "/dev/urandom"), ctx);
            assertThat((String) result).contains("blocked");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("path outside workspace rejected")
    void pathOutsideWorkspace() throws Exception {
        ToolContext.bind(ctx);
        try {
            Object result = tool.execute(
                    java.util.Map.of("path", "/etc/passwd"), ctx);
            assertThat((String) result).contains("outside the workspace");
        } finally {
            ToolContext.unbind();
        }
    }

    @Test
    @DisplayName("name/read_only match Python")
    void matchesPythonSpec() {
        assertThat(tool.name()).isEqualTo("read_file");
        assertThat(tool.isReadOnly()).isTrue();
        assertThat(tool.description()).contains("Read a file");
    }
}
