package com.nanobot.agent.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SessionManagerTest {

    private SessionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SessionManager(Path.of("/tmp/test-workspace"));
    }

    // -- SessionManager CRUD (in-memory) --

    @Test
    void getOrCreateNewSession() {
        Session session = manager.getOrCreate("test-key");
        assertThat(session).isNotNull();
        assertThat(session.key()).isEqualTo("test-key");
    }

    @Test
    void getOrCreateReturnsSameSession() {
        Session s1 = manager.getOrCreate("test-key");
        Session s2 = manager.getOrCreate("test-key");
        assertThat(s1).isSameAs(s2);
    }

    @Test
    void getReturnsNullForUnknownKey() {
        assertThat(manager.get("unknown")).isNull();
    }

    @Test
    void saveAndRetrieve() {
        Session session = new Session("save-key");
        manager.save(session);
        assertThat(manager.get("save-key")).isSameAs(session);
    }

    @Test
    void deleteRemovesSession() {
        Session session = manager.getOrCreate("del-key");
        manager.delete("del-key");
        assertThat(manager.get("del-key")).isNull();
    }

    @Test
    void existsReturnsTrueForSavedSession() {
        manager.getOrCreate("exists-key");
        assertThat(manager.exists("exists-key")).isTrue();
        assertThat(manager.exists("nonexistent")).isFalse();
    }

    // -- Session messages --

    @Test
    void messagesInitiallyEmpty() {
        Session session = new Session("msg-key");
        assertThat(session.messages()).isEmpty();
    }

    @Test
    void addMessageAppendsToMessages() {
        Session session = new Session("msg-key");
        session.addMessage("user", "Hello");
        assertThat(session.messages()).hasSize(1);
        assertThat(session.messages().get(0)).containsEntry("role", "user");
        assertThat(session.messages().get(0)).containsEntry("content", "Hello");
    }

    @Test
    void addMessageSetsTimestamp() {
        Session session = new Session("msg-key");
        session.addMessage("user", "Hello");
        assertThat(session.messages().get(0)).containsKey("timestamp");
    }

    @Test
    void addMessageWithExtra() {
        Session session = new Session("msg-key");
        session.addMessage("user", "Hello",
                new java.util.LinkedHashMap<>() {{ put("command", true); }});
        assertThat(session.messages().get(0)).containsEntry("command", true);
    }

    @Test
    void getHistoryLimitsMaxMessages() {
        Session session = new Session("hist-key");
        for (int i = 0; i < 10; i++) {
            session.addMessage("user", "msg-" + i);
        }
        var history = session.getHistory(5, 0, false);
        assertThat(history).hasSize(5);
        assertThat(history.get(0)).containsEntry("content", "msg-5");
    }

    @Test
    void getHistoryIncludesTimestampsWhenRequested() {
        Session session = new Session("hist-key");
        session.addMessage("user", "Hello");
        var withTs = session.getHistory(10, 0, true);
        // Timestamp is embedded in content string for user messages
        assertThat((String) withTs.get(0).get("content")).contains("[Message Time:");
        var withoutTs = session.getHistory(10, 0, false);
        assertThat((String) withoutTs.get(0).get("content")).doesNotContain("[Message Time:");
    }

    @Test
    void truncateMessagesRemovesOldest() {
        Session session = new Session("trunc-key");
        for (int i = 0; i < 10; i++) {
            session.addMessage("user", "msg-" + i);
        }
        session.truncateMessages(5);
        assertThat(session.messages()).hasSize(5);
        assertThat(session.messages().get(0)).containsEntry("content", "msg-5");
    }

    @Test
    void sessionHasCreatedAndUpdatedTimestamps() {
        Session session = new Session("ts-key");
        assertThat(session.createdAt()).isNotNull();
        assertThat(session.updatedAt()).isNotNull();
    }

    @Test
    void addMessageUpdatesUpdatedAt() throws InterruptedException {
        Session session = new Session("ts-key");
        var before = session.updatedAt();
        Thread.sleep(10);
        session.addMessage("user", "Hello");
        assertThat(session.updatedAt()).isAfter(before);
    }

    @Test
    void metadataIsInitiallyEmpty() {
        Session session = new Session("meta-key");
        assertThat(session.metadata()).isEmpty();
    }

    // -- Session.lastConsolidated --

    @Test
    void lastConsolidatedDefaultsToZero() {
        Session session = new Session("lc-key");
        assertThat(session.lastConsolidated()).isZero();
    }

    @Test
    void lastConsolidatedResetsOnClear() {
        Session session = new Session("lc-key");
        session.addMessage("user", "Hello");
        session.setLastConsolidated(1);
        session.clear();
        assertThat(session.lastConsolidated()).isZero();
    }

    // -- Session.clear() --

    @Test
    void clearRemovesAllMessages() {
        Session session = new Session("clear-key");
        session.addMessage("user", "Hello");
        session.addMessage("assistant", "Hi");
        session.clear();
        assertThat(session.messages()).isEmpty();
    }

    @Test
    void clearResetsMetadata() {
        Session session = new Session("clear-key");
        session.metadata().put("_last_summary", "some summary");
        session.clear();
        assertThat(session.metadata()).doesNotContainKey("_last_summary");
    }

    // -- Session.getHistory with orphan tool result cleanup --

    @Test
    void getHistoryDropsOrphanToolResultsAtFront() {
        Session session = new Session("orphan-key");
        session.addMessage("user", "Hello");
        session.messages().add(0, makeToolResult("orphan-id", "orphan result"));
        var history = session.getHistory(10, 0, false);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("role", "user");
    }

    @Test
    void getHistoryKeepsToolResultsWithMatchingAssistantCall() {
        Session session = new Session("legal-key");
        session.addMessage("assistant", "calling tool",
                Map.of("tool_calls", List.of(Map.of("id", "call_1", "name", "read"))));
        session.addMessage("tool", "result content",
                Map.of("tool_call_id", "call_1", "name", "read"));
        var history = session.getHistory(10, 0, false);
        assertThat(history).hasSize(2);
    }

    // -- Session.getHistory filters _command messages --

    @Test
    void getHistoryFiltersCommandMessages() {
        Session session = new Session("cmd-key");
        session.addMessage("user", "/help");
        session.messages().get(0).put("_command", true);
        session.addMessage("user", "real message");
        var history = session.getHistory(10, 0, false);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("content", "real message");
    }

    // -- Session.getHistory user-turn alignment --

    @Test
    void getHistoryKeepsChannelDeliveryBeforeUserTurn() {
        Session session = new Session("align-key");
        // _channel_delivery before user turn should be KEPT (Python behavior)
        session.addMessage("assistant", "proactive delivery");
        session.messages().get(0).put("_channel_delivery", true);
        session.addMessage("user", "first user");
        session.addMessage("assistant", "reply");
        var history = session.getHistory(10, 0, false);
        // First entry is the _channel_delivery, second is user
        assertThat(history.get(0)).containsEntry("role", "assistant");
        assertThat(history.get(1)).containsEntry("role", "user");
    }

    // -- Session.getHistory with media breadcrumbs --

    @Test
    void getHistorySynthesizesImageBreadcrumbs() {
        Session session = new Session("media-key");
        session.addMessage("user", "check this",
                Map.of("media", List.of("/path/to/photo.jpg")));
        var history = session.getHistory(10, 0, false);
        assertThat(history.get(0)).containsEntry("role", "user");
        String content = (String) history.get(0).get("content");
        assertThat(content).contains("[image: /path/to/photo.jpg]");
    }

    // -- Session.getHistory with CLI app breadcrumbs --

    @Test
    void getHistorySynthesizesCliAppBreadcrumbs() {
        Session session = new Session("cli-key");
        session.addMessage("user", "run cli",
                Map.of("cli_apps", List.of(
                        Map.of("name", "myapp", "entry_point", "/usr/bin/myapp"))));
        var history = session.getHistory(10, 0, false);
        String content = (String) history.get(0).get("content");
        assertThat(content).contains("[CLI App Attachment:");
        assertThat(content).contains("myapp");
    }

    // -- Session.getHistory with MCP preset breadcrumbs --

    @Test
    void getHistorySynthesizesMcpPresetBreadcrumbs() {
        Session session = new Session("mcp-key");
        session.addMessage("user", "use mcp",
                Map.of("mcp_presets", List.of(
                        Map.of("name", "filesystem", "transport", "stdio"))));
        var history = new ArrayList<>(session.getHistory(10, 0, false));
        String content = (String) history.get(0).get("content");
        assertThat(content).contains("[MCP Preset Attachment:");
        assertThat(content).contains("filesystem");
    }

    // -- Session.getHistory timestamp annotation for user roles --

    @Test
    void getHistoryAnnotatesTimestampOnUserMessagesOnly() {
        Session session = new Session("ts-annot-key");
        session.addMessage("user", "Hello");
        session.addMessage("assistant", "Hi there");
        var history = session.getHistory(10, 0, true);
        String userContent = (String) history.get(0).get("content");
        String assistantContent = (String) history.get(1).get("content");
        assertThat(userContent).contains("[Message Time:");
        assertThat(assistantContent).doesNotContain("[Message Time:");
    }

    // -- Session.getHistory with token budget --

    @Test
    void getHistoryRespectsTokenBudget() {
        Session session = new Session("token-key");
        // Add 20 messages with ~100 chars each = ~500 chars total = ~125 tokens
        for (int i = 0; i < 20; i++) {
            session.addMessage("user", "message number " + i + " with some extra padding to increase length");
        }
        // Request history with very tight token budget (~10 tokens = 40 chars)
        var history = session.getHistory(100, 10, false);
        // Should return fewer than 20 messages due to token limit
        assertThat(history.size()).isLessThan(20);
    }

    // -- Session retainRecentLegalSuffix --

    @Test
    void retainRecentLegalSuffixTrimsOldMessages() {
        Session session = new Session("retain-key");
        for (int i = 0; i < 10; i++) {
            session.addMessage("user", "msg-" + i);
        }
        var result = session.retainRecentLegalSuffix(5);
        assertThat(session.messages()).hasSize(5);
        List<Map<String, Object>> dropped = (List<Map<String, Object>>) result.get("dropped");
        int alreadyConsolidated = (int) result.get("alreadyConsolidated");
        assertThat(dropped).hasSize(5);
        assertThat(alreadyConsolidated).isZero();
    }

    @Test
    void retainRecentLegalSuffixZeroClearsAll() {
        Session session = new Session("retain-zero-key");
        session.addMessage("user", "Hello");
        var result = session.retainRecentLegalSuffix(0);
        assertThat(session.messages()).isEmpty();
        List<Map<String, Object>> dropped = (List<Map<String, Object>>) result.get("dropped");
        assertThat(dropped).hasSize(1);
    }

    @Test
    void retainRecentLegalSuffixWithinLimitReturnsEmpty() {
        Session session = new Session("retain-fit-key");
        session.addMessage("user", "msg-1");
        session.addMessage("user", "msg-2");
        var result = session.retainRecentLegalSuffix(10);
        List<Map<String, Object>> dropped = (List<Map<String, Object>>) result.get("dropped");
        assertThat(dropped).isEmpty();
    }

    // -- Session enforceFileCap --

    @Test
    void enforceFileCapTrimsWhenExceeded() {
        Session session = new Session("filecap-key");
        for (int i = 0; i < 10; i++) {
            session.addMessage("user", "msg-" + i);
        }
        List<List<Map<String, Object>>> archived = new ArrayList<>();
        session.enforceFileCap(archived::add, 5);
        assertThat(session.messages()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void enforceFileCapWithinLimitDoesNothing() {
        Session session = new Session("filecap-ok-key");
        session.addMessage("user", "msg-1");
        List<List<Map<String, Object>>> archived = new ArrayList<>();
        session.enforceFileCap(archived::add, 10);
        assertThat(archived).isEmpty();
        assertThat(session.messages()).hasSize(1);
    }

    // -- findLegalMessageStart --

    @Test
    void findLegalMessageStartDropsOrphanToolResults() {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(makeToolResult("orphan-1", "result"));
        messages.add(Map.of("role", "user", "content", "real message"));
        int start = Session.findLegalMessageStart(messages);
        assertThat(start).isEqualTo(1);
    }

    // -- SessionManager with file persistence --

    @Test
    void saveAndLoadFromDisk(@TempDir Path tempDir) {
        SessionManager fm = new SessionManager(tempDir);
        Session session = fm.getOrCreate("file-test");
        session.addMessage("user", "persisted message");
        fm.save(session);
        fm.invalidate("file-test");

        Session loaded = fm.getOrCreate("file-test");
        assertThat(loaded.messages()).hasSize(1);
        assertThat(loaded.messages().get(0)).containsEntry("content", "persisted message");
    }

    @Test
    void deleteSessionRemovesFile(@TempDir Path tempDir) {
        SessionManager fm = new SessionManager(tempDir);
        fm.getOrCreate("delete-file-test");
        fm.save(fm.get("delete-file-test"));
        assertThat(fm.exists("delete-file-test")).isTrue();

        fm.deleteSession("delete-file-test");
        assertThat(fm.exists("delete-file-test")).isFalse();
    }

    @Test
    void flushAllSavesAllCached(@TempDir Path tempDir) {
        SessionManager fm = new SessionManager(tempDir);
        fm.getOrCreate("flush-1").addMessage("user", "one");
        fm.getOrCreate("flush-2").addMessage("user", "two");
        fm.flushAll();
        fm.invalidate("flush-1");
        fm.invalidate("flush-2");

        assertThat(fm.getOrCreate("flush-1").messages()).hasSize(1);
        assertThat(fm.getOrCreate("flush-2").messages()).hasSize(1);
    }

    @Test
    void repairSkipsCorruptLines(@TempDir Path tempDir) throws Exception {
        Path sessionsDir = tempDir.resolve("sessions");
        sessionsDir.toFile().mkdirs();
        // safeKey("repair-test") = "repair-test" ('-' is not replaced)
        Path file = sessionsDir.resolve("repair-test.jsonl");
        // Write metadata + valid message + corrupt line + valid message
        java.nio.file.Files.writeString(file,
                "{\"_type\":\"metadata\",\"key\":\"repair-test\",\"created_at\":\"2025-01-01T00:00:00Z\",\"updated_at\":\"2025-01-01T00:00:00Z\",\"metadata\":{},\"last_consolidated\":0}\n" +
                "{\"role\":\"user\",\"content\":\"good message\",\"timestamp\":\"2025-01-01T00:00:00Z\"}\n" +
                "NOT VALID JSON {{{{{\n" +
                "{\"role\":\"assistant\",\"content\":\"another good one\",\"timestamp\":\"2025-01-01T00:00:00Z\"}\n");
        SessionManager fm = new SessionManager(tempDir);
        Session session = fm.getOrCreate("repair-test");
        assertThat(session.messages()).hasSize(2);
    }

    @Test
    void listSessionsReturnsAll(@TempDir Path tempDir) {
        SessionManager fm = new SessionManager(tempDir);
        fm.getOrCreate("list-1").addMessage("user", "hello one");
        fm.getOrCreate("list-2").addMessage("user", "hello two");
        fm.save(fm.get("list-1"));
        fm.save(fm.get("list-2"));

        var sessions = fm.listSessions();
        assertThat(sessions).hasSize(2);
    }

    @Test
    void readSessionFileReadsWithoutCaching(@TempDir Path tempDir) {
        SessionManager fm = new SessionManager(tempDir);
        fm.getOrCreate("readonly-key").addMessage("user", "readonly content");
        fm.save(fm.get("readonly-key"));
        fm.invalidate("readonly-key");

        var data = fm.readSessionFile("readonly-key");
        assertThat(data).isNotNull();
        assertThat(data.get("messages")).isNotNull();
        @SuppressWarnings("unchecked")
        var msgs = (List<Map<String, Object>>) data.get("messages");
        assertThat(msgs).hasSize(1);
    }

    @Test
    void safeKeyReplacesColons() {
        assertThat(SessionManager.safeKey("discord:12345")).isEqualTo("discord_12345");
    }

    // -- helpers --

    private Map<String, Object> makeToolResult(String toolCallId, String content) {
        var msg = new LinkedHashMap<String, Object>();
        msg.put("role", "tool");
        msg.put("content", content);
        msg.put("tool_call_id", toolCallId);
        msg.put("timestamp", java.time.Instant.now().toString());
        return msg;
    }
}
