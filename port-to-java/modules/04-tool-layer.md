# P3 — Tool Layer

## 复刻目标

对标 `nanobot/agent/tools/base.py`（Tool 抽象类 + Schema 验证）、`registry.py`（ToolRegistry）、`loader.py`（ToolLoader）。

P3 只实现工具框架 + 3 个最小工具（exec/read_file/write_file），其余工具在 P4-P7 按需补。

## Python 源码对照

### Tool 抽象类

```python
class Tool(ABC):
    name: str = ""              # 子类必须设置
    description: str = ""
    parameters: dict[str, Any]  # JSON Schema

    @classmethod
    def enabled(cls, ctx) -> bool: return True   # 条件启用
    @classmethod
    def create(cls, ctx) -> "Tool": ...           # 工厂方法
    @classmethod
    def config_cls(cls) -> type[BaseModel] | None: return None

    def to_schema(self) -> dict[str, Any]:        # → OpenAI function schema
        return {"type": "function", "function": {
            "name": self.name,
            "description": self.description,
            "parameters": self.parameters,
        }}

    def cast_params(self, params): ...            # 类型转换
    def validate_params(self, params) -> list[str]: ...  # 返回错误列表
    @abstractmethod
    async def execute(self, params, ctx) -> ToolResult: ...

@dataclass
class ToolResult:
    output: Any                  # str | list | dict — 返回给 LLM 的内容
    meta: dict
    persist: bool                # 是否持久化到会话历史
    error: str | None
```

### ToolRegistry

```python
class ToolRegistry:
    def register(tool): ...          # self._tools[tool.name] = tool
    def unregister(name): ...
    def get(name) -> Tool | None
    def has(name) -> bool
    def get_definitions() -> list[dict]  # 稳定排序 + 缓存
    def prepare_call(name, params) -> (Tool | None, cast_params, error | None)
    def _coerce_params(tool, params) -> dict  # string → json.loads
```

### ToolLoader

```python
class ToolLoader:
    def discover() -> list[type[Tool]]:     # pkgutil.iter_modules + issubclass
    def _discover_plugins() -> dict:        # entry_points("nanobot.tools")
    def load(ctx, registry, *, scope="core") -> list[str]  # 实例化 + 注册
```

## Java 实现方案

### 1. Tool 抽象类

```java
// Tool.java
public abstract class Tool {
    protected final String name;
    protected final String description;
    protected final Map<String, Object> parameters;  // JSON Schema

    protected Tool(String name, String description,
                   Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    // === 可覆盖 ===
    /** 对标 Python Tool.enabled(ctx) → 是否在给定上下文中启用 */
    public boolean isEnabled(ToolContext ctx) { return true; }

    /** 对标 Python Tool.config_cls() → 可选的强类型配置 */
    public Class<?> configClass() { return null; }

    // === 稳定方法 ===
    /** 对标 to_schema() → OpenAI function 格式 */
    public Map<String, Object> toSchema() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", name,
                "description", description,
                "parameters", parameters
            )
        );
    }

    /** 对标 cast_params + validate_params → 类型转换 + 验证 */
    public Map<String, Object> castParams(Map<String, Object> params) {
        // 对标 Python _cast_value: 按 JSON Schema type 转换
        return SchemaValidator.cast(params, parameters);
    }

    public List<String> validateParams(Map<String, Object> params) {
        return SchemaValidator.validate(params, parameters);
    }

    // === 核心抽象 ===
    /**
     * 对标 Python execute(self, params, ctx) -> ToolResult.
     * Java 用同步方法，虚拟线程自动调度。
     */
    public abstract ToolResult execute(Map<String, Object> params,
                                       ToolContext ctx) throws Exception;

    // Getters
    public String name() { return name; }
    public String description() { return description; }
    public Map<String, Object> parameters() { return parameters; }
}
```

### 2. ToolResult

```java
// ToolResult.java
public record ToolResult(
    Object output,        // String | List | Map<String, Object>
    @Default Map<String, Object> meta,
    @Default boolean persist,
    @Nullable String error
) {
    public static ToolResult ok(Object output) {
        return new ToolResult(output, Map.of(), true, null);
    }
    public static ToolResult ok(Object output, boolean persist) {
        return new ToolResult(output, Map.of(), persist, null);
    }
    public static ToolResult fail(String error) {
        return new ToolResult(error, Map.of(), false, error);
    }

    public boolean isError() { return error != null; }

    // 对标 ensure_nonempty_tool_result
    public String outputAsString() {
        if (output instanceof String s) return s;
        if (output instanceof List || output instanceof Map) {
            // Jackson serialize
        }
        return String.valueOf(output);
    }
}
```

### 3. ToolContext (对标 contextvars + RequestContext)

```java
// ToolContext.java
public record ToolContext(
    String channel,
    String chatId,
    String sessionKey,
    Path workspace,
    ToolRegistry toolRegistry,
    Map<String, Object> runtimeState
) {
    // ThreadLocal 绑定（对标 Python contextvars.ContextVar）
    // 虚拟线程 1:1 对应一个 turn → ThreadLocal 语义正确
    private static final ThreadLocal<ToolContext> CURRENT = new ThreadLocal<>();

    public static void bind(ToolContext ctx) { CURRENT.set(ctx); }
    public static ToolContext current() { return CURRENT.get(); }
    public static void unbind() { CURRENT.remove(); }
}
```

### 4. SchemaValidator（对标 Schema.validate_json_schema_value）

```java
// SchemaValidator.java
public final class SchemaValidator {
    /** 对标 Python Schema.validate_json_schema_value: 递归验证 */
    public static List<String> validate(Object val,
                                         Map<String, Object> schema,
                                         String path) {
        var errors = new ArrayList<String>();
        var rawType = schema.get("type");
        boolean nullable = rawType instanceof List<?> list
            && list.contains("null")
            || Boolean.TRUE.equals(schema.get("nullable"));

        if (nullable && val == null) return errors;

        String type = resolveType(rawType);
        String label = path.isEmpty() ? "parameter" : path;

        // type check (对标 Python _JSON_TYPE_MAP switch)
        errors.addAll(checkType(val, type, label));

        // constraints (对标 Python enum/min/max/minLength/maxLength)
        if (schema.containsKey("enum")) {
            var enumVals = (List<?>) schema.get("enum");
            if (!enumVals.contains(val))
                errors.add(label + " must be one of " + enumVals);
        }
        if ("object".equals(type)) {
            // recursive into properties
            var props = (Map<String, Object>)
                schema.getOrDefault("properties", Map.of());
            var required = (List<String>)
                schema.getOrDefault("required", List.of());
            for (var key : required) {
                if (!((Map<String, Object>) val).containsKey(key))
                    errors.add("missing required " + subPath(path, key));
            }
            for (var entry : ((Map<String, Object>) val).entrySet()) {
                if (props.containsKey(entry.getKey())) {
                    errors.addAll(validate(entry.getValue(),
                        (Map<String, Object>) props.get(entry.getKey()),
                        subPath(path, entry.getKey())));
                }
            }
        }
        // ... array items validation, integer/string constraints
        return errors;
    }

    /** 对标 Python _cast_value: 按 schema type 转换 */
    public static Map<String, Object> cast(Map<String, Object> params,
                                            Map<String, Object> schema) {
        // 按 properties 的类型信息做 cast（string→int 等）
    }
}
```

### 5. ToolRegistry

完全对标 Python 版，纯数据结构操作：

```java
// ToolRegistry.java
@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile List<Map<String, Object>> cachedDefinitions = null;

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        cachedDefinitions = null;  // invalidate cache
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public boolean has(String name) { return tools.containsKey(name); }

    // 对标 get_definitions(): 稳定排序 + 缓存
    public List<Map<String, Object>> getDefinitions() {
        if (cachedDefinitions != null) return cachedDefinitions;
        var builtins = new ArrayList<Map<String, Object>>();
        var mcp = new ArrayList<Map<String, Object>>();
        for (var tool : tools.values()) {
            var schema = tool.toSchema();
            if (tool.name().startsWith("mcp_")) mcp.add(schema);
            else builtins.add(schema);
        }
        // sort by name
        builtins.sort(Comparator.comparing(this::schemaName));
        mcp.sort(Comparator.comparing(this::schemaName));
        var result = new ArrayList<Map<String, Object>>();
        result.addAll(builtins);
        result.addAll(mcp);
        cachedDefinitions = List.copyOf(result);
        return cachedDefinitions;
    }

    // 对标 prepare_call: resolve → cast → validate
    public PreparedCall prepareCall(String name, Object params) {
        var tool = tools.get(name);
        if (tool == null) {
            var suggestion = suggestName(name);
            return new PreparedCall(null, params, "Tool '" + name + "' not found."
                + (suggestion != null ? " Did you mean '" + suggestion + "'?" : ""));
        }
        // coerce params (string → json)
        var coerced = coerceParams(params);
        if (!(coerced instanceof Map)) {
            return new PreparedCall(tool, coerced,
                "Parameters must be a JSON object, got " + coerced.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        var castParams = tool.castParams((Map<String, Object>) coerced);
        var errors = tool.validateParams(castParams);
        if (!errors.isEmpty()) {
            return new PreparedCall(tool, castParams,
                "Invalid parameters: " + String.join("; ", errors));
        }
        return new PreparedCall(tool, castParams, null);
    }

    public record PreparedCall(@Nullable Tool tool, Object params,
                                @Nullable String error) {}
}
```

### 6. ToolLoader（对标 tool discovery）

```java
// ToolLoader.java
@Component
public class ToolLoader {
    private final ToolRegistry registry;

    /** 对标 Python discover() + load() */
    public List<String> load(ToolContext ctx, String scope) {
        // Java 方式：通过 Spring 自动注入所有 Tool Bean
        // 或显式注册
        var registered = new ArrayList<String>();
        for (var tool : toolBeans) {
            if (!tool.isEnabled(ctx)) continue;
            registry.register(tool);
            registered.add(tool.name());
        }
        return registered;
    }
}
```

Spring 的 `@Component` 扫描天然替代了 `pkgutil.iter_modules`。每个 Tool 实现加上 `@Component` 即可自动发现。插件机制用 ServiceLoader：

```java
// 外部插件通过 ServiceLoader 加载
// META-INF/services/com.nanobot.agent.tools.Tool
ServiceLoader<Tool> plugins = ServiceLoader.load(Tool.class);
for (var plugin : plugins) {
    registry.register(plugin);
}
```

### 7. 首批工具实现

```java
// ExecTool.java — 对标 shell.py 的 exec 工具
@Component
public class ExecTool extends Tool {
    public ExecTool() {
        super("exec", "Execute a shell command",
            Map.of("type", "object",
                "properties", Map.of(
                    "command", Map.of("type", "string",
                        "description", "The shell command to run"),
                    "cwd", Map.of("type", "string",
                        "description", "Working directory")
                ),
                "required", List.of("command")
            ));
    }

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext ctx)
            throws Exception {
        String command = (String) params.get("command");
        String cwd = (String) params.getOrDefault("cwd",
            ctx.workspace().toString());
        var process = new ProcessBuilder("bash", "-c", command)
            .directory(Path.of(cwd).toFile())
            .redirectErrorStream(true)
            .start();
        // 超时等待
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes());
        if (!finished) {
            process.destroyForcibly();
            return ToolResult.ok(output + "\n[Command timed out after 30s]");
        }
        return ToolResult.ok(output);
    }
}

// FileReadTool.java — 对标 filesystem 的 read_file
@Component
public class FileReadTool extends Tool {
    // ... 构造函数设置 name="read_file", parameters 含 file_path + offset + limit

    @Override
    public ToolResult execute(Map<String, Object> params, ToolContext ctx)
            throws Exception {
        var filePath = ctx.workspace().resolve((String) params.get("file_path"));
        // 安全检查：确保在 workspace 内
        int offset = params.containsKey("offset")
            ? ((Number) params.get("offset")).intValue() : 1;
        int limit = params.containsKey("limit")
            ? ((Number) params.get("limit")).intValue() : 500;
        // read file, return lines
    }
}

// FileWriteTool.java — 对标 filesystem 的 write_file
@Component
public class FileWriteTool extends Tool {
    // ... 构造函数设置 name="write_file"
}
```

## 测试对齐

```java
// ToolRegistryTest.java
class ToolRegistryTest {
    @Test void registerAndGet() { ... }
    @Test void prepareCallInvalidParams() { ... }
    @Test void definitionsCacheInvalidatedOnRegister() { ... }
}

// SchemaValidatorTest.java
class SchemaValidatorTest {
    @Test void validateRequiredFields() { ... }
    @Test void validateEnumConstraint() { ... }
    @Test void validateNestedObject() { ... }
    @Test void nullableType() { ... }
}

// ExecToolTest.java
class ExecToolTest {
    @Test void execSimpleCommand() throws Exception {
        var tool = new ExecTool();
        var result = tool.execute(Map.of("command", "echo hello"),
            new ToolContext(..., Path.of("/tmp")));
        assertTrue(((String) result.output()).contains("hello"));
    }

    @Test void execTimeout() throws Exception {
        var result = new ExecTool().execute(
            Map.of("command", "sleep 60"), ...);
        assertTrue(((String) result.output()).contains("timed out"));
    }
}
```

## 验证标准

```bash
mvn test -Dtest=ExecToolTest,FileReadToolTest,FileWriteToolTest,ToolRegistryTest

# 预期: 全部通过
# exec("echo hello") → "hello\n"
# read_file("/tmp/test.txt") → 文件内容
# write_file(path, content) → 文件创建成功
```

## 代码量估算

- Tool abstract class: ~60 行
- ToolResult record: ~30 行
- ToolContext record: ~25 行
- SchemaValidator: ~100 行
- ToolRegistry: ~100 行
- ToolLoader: ~50 行
- ExecTool: ~40 行
- FileReadTool: ~50 行
- FileWriteTool: ~40 行
- 测试: ~200 行
- **合计: ~695 行**
