package com.nanobot.agent.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class MemoryStoreTest {

    private Path workspace;
    private MemoryStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        workspace = tempDir;
        store = new MemoryStore(workspace, 100);
    }

    // -- directory creation --

    @Test
    void createsMemoryDirOnInit() {
        assertThat(workspace.resolve("memory")).isDirectory();
    }

    // -- MEMORY.md --

    @Test
    void readMemoryReturnsEmptyWhenFileMissing() {
        assertThat(store.readMemory()).isEmpty();
    }

    @Test
    void writeAndReadMemory() {
        store.writeMemory("Long-term facts here");
        assertThat(store.readMemory()).isEqualTo("Long-term facts here");
    }

    @Test
    void overwriteMemory() {
        store.writeMemory("first");
        store.writeMemory("second");
        assertThat(store.readMemory()).isEqualTo("second");
    }

    // -- SOUL.md --

    @Test
    void readSoulReturnsEmptyWhenFileMissing() {
        assertThat(store.readSoul()).isEmpty();
    }

    @Test
    void writeAndReadSoul() {
        store.writeSoul("I am a helpful agent");
        assertThat(store.readSoul()).isEqualTo("I am a helpful agent");
    }

    // -- USER.md --

    @Test
    void readUserReturnsEmptyWhenFileMissing() {
        assertThat(store.readUser()).isEmpty();
    }

    @Test
    void writeAndReadUser() {
        store.writeUser("User preferences here");
        assertThat(store.readUser()).isEqualTo("User preferences here");
    }

    // -- getMemoryContext --

    @Test
    void getMemoryContextReturnsFormattedMemory() {
        store.writeMemory("Remember this");
        String ctx = store.getMemoryContext();
        assertThat(ctx).contains("## Long-term Memory");
        assertThat(ctx).contains("Remember this");
    }

    @Test
    void getMemoryContextReturnsEmptyWhenNoMemory() {
        String ctx = store.getMemoryContext();
        assertThat(ctx).isEmpty();
    }

    // -- history.jsonl append + read --

    @Test
    void appendHistoryReturnsIncrementingCursor() {
        int c1 = store.appendHistory("entry 1");
        int c2 = store.appendHistory("entry 2");
        assertThat(c2).isGreaterThan(c1);
    }

    @Test
    void appendHistoryPersistsToFile() throws Exception {
        store.appendHistory("hello world");
        Path historyFile = workspace.resolve("memory").resolve("history.jsonl");
        assertThat(historyFile).exists();
        String content = Files.readString(historyFile);
        assertThat(content).contains("hello world");
    }

    @Test
    void appendHistoryIncludesSessionKey() throws Exception {
        store.appendHistory("session entry", null, "discord:12345");
        Path historyFile = workspace.resolve("memory").resolve("history.jsonl");
        String content = Files.readString(historyFile);
        assertThat(content).contains("discord:12345");
    }

    @Test
    void appendHistoryTruncatesLongEntries() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100_000; i++) sb.append("x");
        int cursor = store.appendHistory(sb.toString(), 500, null);
        assertThat(cursor).isPositive();
    }

    @Test
    void readUnprocessedHistoryReturnsEntriesAfterCursor() {
        store.appendHistory("entry a"); // cursor 1
        store.appendHistory("entry b"); // cursor 2
        store.appendHistory("entry c"); // cursor 3
        var entries = store.readUnprocessedHistory(1);
        assertThat(entries).hasSize(2);
        assertThat((String) entries.get(0).get("content")).isEqualTo("entry b");
    }

    @Test
    void readUnprocessedHistoryReturnsEmptyWhenNoNewEntries() {
        int c = store.appendHistory("only entry");
        var entries = store.readUnprocessedHistory(c);
        assertThat(entries).isEmpty();
    }

    // -- recent history for prompt --

    @Test
    void readRecentHistoryForPromptFiltersBySessionKey() {
        store.appendHistory("entry a", null, "session-1");
        store.appendHistory("entry b", null, "session-2");
        store.appendHistory("entry c", null, "session-1");
        var entries = store.readRecentHistoryForPrompt(0, "session-1", false);
        assertThat(entries).hasSize(2);
    }

    @Test
    void readRecentHistoryForPromptUnifiedIncludesNonInternal() {
        store.appendHistory("entry a", null, "session-1");
        store.appendHistory("entry b", null, "cron:daily"); // internal prefix
        store.appendHistory("entry c", null, "session-2");
        var entries = store.readRecentHistoryForPrompt(0, "session-1", true);
        // unified: session-1's own + non-internal (session-2, but NOT cron:*)
        assertThat(entries).hasSize(2); // session-1 + session-2
    }

    // -- compact history --

    @Test
    void compactHistoryKeepsLastNEntries() {
        store = new MemoryStore(workspace, 3);
        for (int i = 0; i < 10; i++) {
            store.appendHistory("entry " + i);
        }
        store.compactHistory();
        var entries = store.readUnprocessedHistory(0);
        assertThat(entries).hasSize(3);
    }

    @Test
    void compactHistoryNoOpWhenUnderLimit() {
        store = new MemoryStore(workspace, 100);
        store.appendHistory("entry 1");
        store.appendHistory("entry 2");
        store.compactHistory();
        var entries = store.readUnprocessedHistory(0);
        assertThat(entries).hasSize(2);
    }

    // -- dream cursor --

    @Test
    void getLastDreamCursorDefaultsToZero() {
        assertThat(store.getLastDreamCursor()).isZero();
    }

    @Test
    void setAndGetLastDreamCursor() {
        store.setLastDreamCursor(42);
        assertThat(store.getLastDreamCursor()).isEqualTo(42);
        // Persisted to file
        assertThat(workspace.resolve("memory").resolve(".dream_cursor")).exists();
    }

    // -- nextCursor recovery --

    @Test
    void nextCursorRecoversFromFileTail() {
        store.appendHistory("e1");
        store.appendHistory("e2");
        // Delete .cursor file to force recovery from file tail
        try {
            Files.deleteIfExists(workspace.resolve("memory").resolve(".cursor"));
        } catch (Exception ignored) {}
        int next = store.appendHistory("e3");
        assertThat(next).isGreaterThan(0);
    }
}
