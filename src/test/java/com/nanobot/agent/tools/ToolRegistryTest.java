package com.nanobot.agent.tools;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ToolRegistry")
class ToolRegistryTest {

    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    @DisplayName("register and get tool")
    void registerAndGet() {
        var tool = new TestTool("test_tool", "A test tool");
        registry.register(tool);
        assertThat(registry.get("test_tool")).isSameAs(tool);
        assertThat(registry.has("test_tool")).isTrue();
    }

    @Test
    @DisplayName("get missing tool returns null")
    void getMissing() {
        assertThat(registry.get("nonexistent")).isNull();
        assertThat(registry.has("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("unregister removes tool")
    void unregister() {
        var tool = new TestTool("t1", "desc");
        registry.register(tool);
        registry.unregister("t1");
        assertThat(registry.has("t1")).isFalse();
    }

    @Test
    @DisplayName("toolNames returns all registered names")
    void toolNames() {
        registry.register(new TestTool("a", "desc"));
        registry.register(new TestTool("b", "desc"));
        assertThat(registry.toolNames()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("size returns count")
    void size() {
        assertThat(registry.size()).isEqualTo(0);
        registry.register(new TestTool("x", "desc"));
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("getDefinitions returns stable sorted: builtins first, then MCP")
    void getDefinitionsOrder() {
        registry.register(new TestTool("zebra", "desc"));
        registry.register(new TestTool("mcp_search", "desc"));
        registry.register(new TestTool("alpha", "desc"));

        var defs = registry.getDefinitions();
        assertThat(defs).hasSize(3);
        // builtins sorted first
        assertThat(ToolRegistry.schemaName(defs.get(0))).isEqualTo("alpha");
        assertThat(ToolRegistry.schemaName(defs.get(1))).isEqualTo("zebra");
        // MCP tools after
        assertThat(ToolRegistry.schemaName(defs.get(2))).isEqualTo("mcp_search");
    }

    @Test
    @DisplayName("definitions cache invalidated on register")
    void definitionsCacheInvalidatedOnRegister() {
        registry.register(new TestTool("a", "desc"));
        var first = registry.getDefinitions();
        registry.register(new TestTool("b", "desc"));
        var second = registry.getDefinitions();
        assertThat(second).hasSize(2);
    }

    @Test
    @DisplayName("definitions cache invalidated on unregister")
    void definitionsCacheInvalidatedOnUnregister() {
        registry.register(new TestTool("a", "desc"));
        registry.getDefinitions();
        registry.unregister("a");
        assertThat(registry.getDefinitions()).isEmpty();
    }

    @Nested
    @DisplayName("prepareCall")
    class PrepareCallTests {

        @Test
        @DisplayName("tool not found with suggestion")
        void toolNotFound() {
            registry.register(new TestTool("hello_world", "desc"));
            var result = registry.prepareCall("helloworld", Map.of());
            assertThat(result.error()).contains("not found");
            assertThat(result.error()).contains("Did you mean 'hello_world'");
        }

        @Test
        @DisplayName("valid params return prepared call without error")
        void validParams() {
            registry.register(new TestTool("greet", "desc"));
            var result = registry.prepareCall("greet", Map.of("name", "world"));
            assertThat(result.error()).isNull();
            assertThat(result.tool()).isNotNull();
        }

        @Test
        @DisplayName("string params coerced from JSON")
        void stringParamsCoerced() {
            registry.register(new TestTool("json_in", "desc"));
            var result = registry.prepareCall("json_in", "{\"key\": \"val\"}");
            assertThat(result.error()).isNull();
            assertThat(result.params()).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("non-object params rejected")
        void nonObjectParamsRejected() {
            registry.register(new TestTool("t", "desc"));
            var result = registry.prepareCall("t", "\"just_a_string\"");
            assertThat(result.error()).contains("must be a JSON object");
        }
    }

    @Nested
    @DisplayName("execute")
    class ExecuteTests {

        @Test
        @DisplayName("execute returns tool result on success")
        void executeSuccess() {
            registry.register(new TestTool("echo", "desc") {
                @Override
                public Object execute(Map<String, Object> params, ToolContext ctx) {
                    return "result: " + params.get("msg");
                }
            });
            Object result = registry.execute("echo", Map.of("msg", "hello"));
            assertThat(result).isEqualTo("result: hello");
        }

        @Test
        @DisplayName("execute returns error for missing tool")
        void executeMissingTool() {
            Object result = registry.execute("no_such_tool", Map.of());
            assertThat((String) result).contains("not found");
        }

        @Test
        @DisplayName("execute returns error on exception")
        void executeException() {
            registry.register(new TestTool("crash", "desc") {
                @Override
                public Object execute(Map<String, Object> params, ToolContext ctx) {
                    throw new RuntimeException("boom");
                }
            });
            Object result = registry.execute("crash", Map.of());
            assertThat((String) result).contains("Error executing crash");
        }
    }

    // ---- minimal Tool subclass for testing ----

    static class TestTool extends Tool {
        private final String name;
        private final String desc;

        TestTool(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override
        public String name() { return name; }

        @Override
        public String description() { return desc; }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object", "properties", Map.of(
                    "name", Map.of("type", "string")), "required", List.of());
        }

        @Override
        public Object execute(Map<String, Object> params, ToolContext ctx) {
            return "ok";
        }
    }
}
