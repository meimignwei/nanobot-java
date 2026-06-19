package com.nanobot.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class ContextBuilderTest {

    private Path workspace;
    private ContextBuilder builder;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workspace = tempDir;
        builder = new ContextBuilder(workspace, null);
    }

    // -- loadBootstrapFiles --

    @Test
    void loadBootstrapFilesReturnsEmptyWhenNoFiles() {
        assertThat(builder.loadBootstrapFiles(workspace)).isEmpty();
    }

    @Test
    void loadBootstrapFilesReadsAgentsMd() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "You are a helpful bot");
        String result = builder.loadBootstrapFiles(workspace);
        assertThat(result).contains("## AGENTS.md");
        assertThat(result).contains("You are a helpful bot");
    }

    @Test
    void loadBootstrapFilesReadsMultipleFiles() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "agent instructions");
        Files.writeString(workspace.resolve("SOUL.md"), "soul content");
        String result = builder.loadBootstrapFiles(workspace);
        assertThat(result).contains("AGENTS.md");
        assertThat(result).contains("SOUL.md");
    }

    // -- buildRuntimeContext --

    @Test
    void buildRuntimeContextIncludesTimeChannelAndChatId() {
        String ctx = ContextBuilder.buildRuntimeContext("discord", "chat-123", null, "user-1", null);
        assertThat(ctx).contains("[Runtime Context");
        assertThat(ctx).contains("Current Time:");
        assertThat(ctx).contains("Channel: discord");
        assertThat(ctx).contains("Chat ID: chat-123");
        assertThat(ctx).contains("Sender ID: user-1");
        assertThat(ctx).contains("[/Runtime Context]");
    }

    @Test
    void buildRuntimeContextOmitsNullFields() {
        String ctx = ContextBuilder.buildRuntimeContext(null, null, null, null, null);
        assertThat(ctx).contains("[Runtime Context");
        assertThat(ctx).doesNotContain("Channel:");
        assertThat(ctx).doesNotContain("Chat ID:");
        assertThat(ctx).doesNotContain("Sender ID:");
    }

    // -- buildSystemPrompt --

    @Test
    void buildSystemPromptContainsIdentitySection() {
        String prompt = builder.buildSystemPrompt(null, null, null, workspace, true, null, false);
        assertThat(prompt).isNotEmpty();
        assertThat(prompt).contains(workspace.toString());
    }

    @Test
    void buildSystemPromptIncludesBootstrapFiles() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "custom agent rules");
        String prompt = builder.buildSystemPrompt(null, null, null, workspace, true, null, false);
        assertThat(prompt).contains("custom agent rules");
    }

    @Test
    void buildSystemPromptIncludesMemoryContext() throws Exception {
        builder.getMemory().writeMemory("remembered fact");
        String prompt = builder.buildSystemPrompt(null, null, null, workspace, true, null, false);
        assertThat(prompt).contains("remembered fact");
    }

    // -- buildUserContent --

    @Test
    void buildUserContentReturnsPlainTextWhenNoMedia() {
        var result = builder.buildUserContent("hello", null);
        assertThat(result).isInstanceOf(String.class);
        assertThat((String) result).isEqualTo("hello");
    }

    @Test
    void buildUserContentReturnsTextWhenMediaFileNotFound() {
        var result = builder.buildUserContent("hello", List.of("/nonexistent/photo.jpg"));
        assertThat(result).isInstanceOf(String.class);
    }

    // -- buildMessages --

    @Test
    void buildMessagesAssemblesSystemAndHistory() {
        var history = new ArrayList<Map<String, Object>>();
        history.add(Map.of("role", "assistant", "content", "previous response"));
        var messages = builder.buildMessages(history, "current message",
                null, null, null, null, "user", null, null, null, null, workspace, null, null, false, true, null, false);
        // system + history(1 assistant) + current user = 3 (roles differ, no merge)
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).containsEntry("role", "system");
        assertThat(messages.get(1)).containsEntry("role", "assistant");
        assertThat(messages.get(2)).containsEntry("role", "user");
    }

    @Test
    void buildMessagesMergesWhenLastHistoryRoleMatches() {
        var history = new ArrayList<Map<String, Object>>();
        history.add(Map.of("role", "user", "content", "previous"));
        var messages = builder.buildMessages(history, "current",
                null, null, null, null, "user", null, null, null, null, workspace, null, null, false, true, null, false);
        // History has 1 user msg, current_role is "user" → merge into last history msg
        assertThat(messages).hasSize(2); // system + merged user
        String mergedContent = (String) messages.get(1).get("content");
        assertThat(mergedContent).contains("previous");
        assertThat(mergedContent).contains("current");
    }

    // -- detectImageMime --

    @Test
    void detectImageMimeReturnsNullForNonImage() {
        byte[] data = "plain text, not an image".getBytes();
        assertThat(ContextBuilder.detectImageMime(data)).isNull();
    }

    @Test
    void detectImageMimeDetectsPng() {
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(ContextBuilder.detectImageMime(png)).isEqualTo("image/png");
    }

    @Test
    void detectImageMimeDetectsJpeg() {
        // JPEG magic: FF D8 FF
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
        assertThat(ContextBuilder.detectImageMime(jpeg)).isEqualTo("image/jpeg");
    }

    @Test
    void detectImageMimeDetectsGif() {
        // GIF magic: 47 49 46 38
        byte[] gif = {0x47, 0x49, 0x46, 0x38};
        assertThat(ContextBuilder.detectImageMime(gif)).isEqualTo("image/gif");
    }

    @Test
    void detectImageMimeDetectsWebP() {
        // WebP magic: 52 49 46 46 ... 57 45 42 50
        byte[] webp = {0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50};
        assertThat(ContextBuilder.detectImageMime(webp)).isEqualTo("image/webp");
    }
}
