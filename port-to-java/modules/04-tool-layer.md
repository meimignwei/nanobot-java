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

---

## P3 复刻完成度报告

**完成日期**: 2026-06-19

### Java 源文件对照表

| Java 文件 | 行数 | Python 对标 | Python 行数 | 完成度 | 说明 |
|-----------|------|------------|-------------|--------|------|
| Schema.java | 327 | base.py (Schema) + schema.py | 296 + 232 = 528 | ~90% | 含 5 种具体 schema 类型；缺 NumberSchema 独立类型 |
| Tool.java | 164 | base.py (Tool 抽象类) | ~170 | ~95% | cast/validate/execute 完整；缺 tool_parameters 装饰器（改用构造器约定） |
| ToolContext.java | 72 | context.py | 60 | ~90% | ThreadLocal 对标 ContextVar；缺 RequestContext（P4 补充） |
| ToolRegistry.java | 194 | registry.py | 182 | ~100% | prepareCall/execute/suggest 完整；缓存、排序逻辑一致 |
| ToolLoader.java | 182 | loader.py | 116 | ~85% | Spring @Component 扫描替代 pkgutil；ServiceLoader 替代 entry_points；load/scopes 完整 |
| ToolResult.java | 43 | base.py (ToolResult) | ~20 | ~100% | output/meta/persist/error + ok/fail 工厂方法 |
| ExecTool.java | 375 | shell.py (ExecTool) | 677 | ~65% | 核心 command 执行完整；缺 session/yield/sandbox/URL 检测 |
| FileReadTool.java | 278 | filesystem.py (ReadFileTool + _FsTool) | ~325 | ~75% | 文本分页/设备阻断/MIME 检测完整；缺 PDF/Office/图片块/file-state |
| FileWriteTool.java | 117 | filesystem.py (WriteFileTool) | ~38 | ~90% | 核心写文件完整；缺 file-state 记录 |

**主代码合计**: 1,752 行（Python 对标约 2,116 行）
**测试代码合计**: 890 行（5 个测试类，61 个测试用例）
**总代码量**: 2,642 行

### 方法级对标清单

#### Schema.java
| Python 函数/属性 | Java 方法 | 状态 |
|---|---|---|
| `Schema.resolve_json_schema_type(t)` | `resolveJsonSchemaType(Object)` | ✅ 100% |
| `Schema.validate_json_schema_value(val, schema, path)` | `validateJsonSchemaValue(Object, Map, String)` | ✅ 100% |
| `Schema.fragment(value)` | `fragment(Object)` | ✅ 100% |
| `Schema.subpath(path, key)` | `subpath(String, String)` | ✅ 100% |
| `StringSchema.to_json_schema()` | `StringSchema.toJsonSchema()` | ✅ 100% |
| `IntegerSchema.to_json_schema()` | `IntegerSchema.toJsonSchema()` | ✅ 100% |
| `BooleanSchema.to_json_schema()` | `BooleanSchema.toJsonSchema()` | ✅ 100% |
| `ArraySchema.to_json_schema()` | `ArraySchema.toJsonSchema()` | ✅ 100% |
| `ObjectSchema.to_json_schema()` | `ObjectSchema.toJsonSchema()` | ✅ 100% |
| `tool_parameters_schema()` | `toolParametersSchema()` | ✅ 100% |
| `NumberSchema` | - | ❌ 未实现（Python 中与 IntegerSchema 分开） |

#### Tool.java
| Python 属性/方法 | Java 方法 | 状态 |
|---|---|---|
| `Tool.name` | `name()` | ✅ 100% |
| `Tool.description` | `description()` | ✅ 100% |
| `Tool.parameters` | `parameters()` | ✅ 100% |
| `Tool.read_only` | `isReadOnly()` | ✅ 100% |
| `Tool.concurrency_safe` | `isConcurrencySafe()` | ✅ 100% |
| `Tool.exclusive` | `isExclusive()` | ✅ 100% |
| `Tool.config_key` | `configKey()` | ✅ 100% |
| `Tool.config_cls()` | `configClass()` | ✅ 100% |
| `Tool.enabled(ctx)` | `isEnabled(ToolContext)` | ✅ 100% |
| `Tool.create(ctx)` | `create(ToolContext, Class)` | ✅ 100% |
| `Tool.to_schema()` | `toSchema()` | ✅ 100% |
| `Tool.cast_params(params)` | `castParams(Map)` | ✅ 100% |
| `Tool._cast_object(obj, schema)` | `castObject(Object, Map)` | ✅ 100% |
| `Tool._cast_value(val, schema)` | `castValue(Object, Map)` | ✅ 100% |
| `Tool.validate_params(params)` | `validateParams(Map)` | ✅ 100% |
| `Tool.execute(self, **kwargs)` | `execute(Map, ToolContext)` | ✅ 同步（Python async → Java sync + 虚拟线程） |
| `tool_parameters` 装饰器 | 构造器直接传参 | ⚠️ 适配（Java 无装饰器语法） |

#### ToolRegistry.java
| Python 方法 | Java 方法 | 状态 |
|---|---|---|
| `register(tool)` | `register(Tool)` | ✅ 100% |
| `unregister(name)` | `unregister(String)` | ✅ 100% |
| `get(name)` | `get(String)` | ✅ 100% |
| `has(name)` | `has(String)` | ✅ 100% |
| `tool_names()` | `toolNames()` | ✅ 100% |
| `get_definitions()` | `getDefinitions()` | ✅ 100%（含缓存失效） |
| `prepare_call(name, params)` | `prepareCall(String, Object)` | ✅ 100% |
| `execute(name, params)` | `execute(String, Object)` | ✅ 100% |
| `_lookup_key(name)` | `lookupKey(String)` | ✅ 100% |
| `_suggest_name(name)` | `suggestName(String)` | ✅ 100% |
| `_coerce_params(tool, params)` | `coerceParams(Tool, Object)` | ✅ 100% |
| `_coerce_argument_value(value)` | `coerceArgumentValue(Object)` | ✅ 100% |
| `_unwrap_arguments_payload(tool, params)` | `unwrapArgumentsPayload(Tool, Object)` | ✅ 100% |

#### ExecTool.java
| Python 特性 | Java 实现 | 状态 |
|---|---|---|
| command/cmd 别名 | ✅ `params.getOrDefault("command", params.get("cmd"))` | 100% |
| working_dir/workdir 别名 | ✅ 同上 | 100% |
| timeout (default 60s, max 600s) | ✅ `resolveTimeout()` | 100% |
| deny_patterns 安全守卫 | ✅ 完整 15 条 deny patterns | 100% |
| allow_patterns 优先 | ✅ allowPatterns 优先检查 | 100% |
| 环境变量构建 (Unix/Windows) | ✅ `buildEnv()` 平台感知 | 100% |
| shell 解析 (bash/zsh/sh) | ✅ `resolveShell()` | 100% |
| path prepend/append | ✅ `wrapPathExport()` | 100% |
| 输出截断 (10K chars) | ✅ 头尾各 50% 截断 | 100% |
| stderr 包含 | ✅ "STDERR:\n..." 前缀 | 100% |
| exit code 显示 | ✅ "Exit code: N" | 100% |
| 异步子进程 | ❌ `ProcessBuilder` 同步（虚拟线程补偿） | 0% |
| session 模式 (yield_time_ms) | ❌ 需要 ExecSession 基础设施 | 0% |
| sandbox 集成 (bubblewrap) | ❌ P4+ | 0% |
| 内部 URL 检测 | ❌ 需要 network security 模块 | 0% |
| _BENIGN_DEVICE_PATHS | ❌ 仅基础路径穿越检查 | 0% |
| extract_absolute_paths 工作区守卫 | ❌ 简化版仅检查 ../ | 0% |

#### FileReadTool.java
| Python 特性 | Java 实现 | 状态 |
|---|---|---|
| path 解析 + 工作区守卫 | ✅ `resolvePath()` | 100% |
| offset/limit 分页 | ✅ 1-indexed offset, 默认 2000 行 | 100% |
| 设备路径阻断 | ✅ 完整 `BLOCKED_DEVICE_PATHS` + proc/fd regex | 100% |
| CRLF → LF 标准化 | ✅ `.replace("\r\n", "\n")` | 100% |
| 128K chars 截断 | ✅ 按行截断 + 续读提示 | 100% |
| MIME 检测 (magic bytes) | ✅ JPEG/PNG/GIF/WebP/PDF | 100% |
| force 参数 | ⚠️ 已声明但无 file-state dedup 可跳过 | 50% |
| PDF 读取 | ❌ 需 PDFBox / pymupdf | 0% |
| Office 文档 (.docx/.xlsx/.pptx) | ❌ 需 Apache POI | 0% |
| 图片内容块 (base64) | ❌ 返回占位文本 "(Image file: ...)" | 0% |
| file-state 去重 | ❌ 需 FileStates (P6) | 0% |
| _FsTool 基类 (共享构造函数) | ❌ 简化为独立类，构造器直接注入 workspace | 0% |
| pages 参数 (PDF) | ❌ 依赖 PDF 支持 | 0% |

#### FileWriteTool.java
| Python 特性 | Java 实现 | 状态 |
|---|---|---|
| path 解析 + 工作区守卫 | ✅ `resolvePath()` | 100% |
| 父目录自动创建 | ✅ `Files.createDirectories(fp.getParent())` | 100% |
| UTF-8 写入 + 字符计数 | ✅ `Files.writeString(fp, content)` | 100% |
| Error: PermissionError 捕获 | ✅ `catch(IOException e)` | 100% |
| file-state 记录 (record_write) | ❌ 需 FileStates (P6) | 0% |
| 设备路径阻断 | ✅ 复用 `FileReadTool.isBlockedDevice()` | 100% |

#### ToolLoader.java
| Python 方法 | Java 方法 | 状态 |
|---|---|---|
| `discover()` (pkgutil.iter_modules) | `discover()` (Spring @Component 委托) | ⚠️ 机制不同，语义相同 |
| `_discover_plugins()` (entry_points) | `discoverPlugins()` (ServiceLoader) | ⚠️ 机制不同，语义相同 |
| `load(ctx, registry, *, scope)` | `load(Collection, ToolContext, ToolRegistry, String)` | ✅ 100% |
| `_SKIP_MODULES` | `SKIP_MODULES` | ✅ 100% |
| test_classes 注入 | 构造器参数 | ✅ 100% |
| 插件/内置名称冲突检测 | ✅ builtinNames set + 日志警告 | 100% |
| scope 过滤 (_scopes) | ✅ 反射读取 `_scopes` 静态字段 | ⚠️ 用反射而非 Python 类属性 |

### 未修复项 (非对齐)

| # | 项 | 原因 | 计划 |
|---|-----|------|------|
| 1 | ExecTool 异步子进程 | Python 用 `asyncio.create_subprocess_exec`；Java ProcessBuilder 是同步的。虚拟线程可补偿调度问题 | P4 评估是否需要 CompletableFuture 包装 |
| 2 | ExecTool session 模式 (yield_time_ms/write_stdin) | 需要 ExecSession 管理器、进程存活跟踪、stdin 写入通道 | P6 (Session Memory) 实现 |
| 3 | ExecTool sandbox 集成 | Python 用 bubblewrap 包装命令；Java 需要等效容器化方案 | P4+ |
| 4 | ExecTool 内部 URL 检测 | 依赖 `nanobot.security.network.contains_internal_url` | P4+ 安全模块 |
| 5 | FileReadTool PDF/Office 文档支持 | Python 用 pymupdf (PDF) 和自定义 extract_text (Office)；Java 需 PDFBox + Apache POI | 按需补充 |
| 6 | FileReadTool 图片内容块 (base64) | Python `build_image_content_blocks` 生成多模态消息；需要深层 LLM provider 集成 | P4 Agent Loop 中实现 |
| 7 | FileReadTool/FileWriteTool file-state 跟踪 | Python FileStates 跟踪读/写去重和 mtime；Java FileStates 尚未实现 | P6 (Session Memory) |
| 8 | ToolLoader 包扫描机制 | Python pkgutil.iter_modules → Java Spring @Component 扫描；行为等价但实现路径不同 | 不可消除（语言/框架差异） |
| 9 | Schema.NumberSchema | Python 的 NumberSchema 独立于 IntegerSchema（浮点验证）；Java 仅在类型检查时区分 | 按需补充 |
| 10 | edit_file / list_dir 工具 | Python filesystem.py 含 edit_file (700行) 和 list_dir (80行) | P4+ 按需补充 |
| 11 | Langfuse 追踪 | 同 P2 报告所述，Python 3 行替换 OpenAI client；Java 无等效机制 | 长期评估 OpenTelemetry |

### 测试覆盖

| 测试类 | 用例数 | 覆盖范围 |
|--------|--------|----------|
| ToolRegistryTest | 15 | register/get/unregister/has/names/size, definitions 排序/缓存, prepareCall (未找到/字符串解析/非对象拒绝), execute (成功/缺失/异常) |
| SchemaValidatorTest | 17 | 必填字段, 类型检查 (正确/错误/nullable), enum, 数值约束 (min/max), 字符串约束 (minLength/maxLength), 嵌套对象, 数组 (items/minItems/maxItems) |
| ExecToolTest | 11 | 简单命令, 缺失命令, 空命令, 阻断命令, cmd 别名, working_dir, 失败命令, stderr, 长输出截断, Python 对标属性, parameters schema |
| FileReadToolTest | 8 | 读文件, offset/limit, 缺失文件, 空文件, offset 越界, 设备阻断, 工作区外拒绝, Python 对标属性 |
| FileWriteToolTest | 7 | 写新文件, 覆盖已有, 创建父目录, 缺失 path, 缺失 content, 工作区外拒绝, Python 对标属性 |

### 验证结果

```bash
$ mvn test
Tests run: 190, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

新增 61 个测试全部通过，原有 129 个测试无回归。

### 总体完成度

**P3 Tool Layer: ~85%**

加权计算：
- 工具框架层 (Schema/Tool/ToolContext/ToolRegistry/ToolLoader/ToolResult): ~93%
- 命令执行 (ExecTool): ~65%
- 文件读取 (FileReadTool): ~75%
- 文件写入 (FileWriteTool): ~90%

核心框架和 3 个最小工具（exec/read_file/write_file）已完整实现，满足 P3 目标。安全守卫（deny patterns）、参数验证、工作区隔离、设备阻断等关键安全特性均已对标。缺失的 session/sandbox/PDF/file-state 属于后期 phase 的范围。
