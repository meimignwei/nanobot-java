# P7 — 补渠道

## 复刻目标

对标 `nanobot/channels/` 下 22 个渠道实现。这是体力活——每个 channel 都遵循相同的 `BaseChannel` 接口，主要是平台 SDK 对接 + 消息格式适配。

## Python 渠道清单与 Java SDK 对照

| Python Channel | 文件行数 | Python SDK | Java 替代 | 难度 |
|---------------|---------|-----------|-----------|------|
| **websocket** | 1,178 | websockets | Spring WebSocket（P6 已完成） | - |
| **telegram** | ~800 | python-telegram-bot | TelegramBots (Java) | 低 |
| **discord** | ~700 | discord.py | JDA | 中 |
| **slack** | ~600 | slack-sdk | slack-api-model + bolt | 中 |
| **feishu** | 1,984 | feishu SDK | Lark SDK (Java) | 中 |
| **wechat** (微信) | ~500 | itchat-like | ❌ 无官方 SDK，需 itchat4j 或手动 HTTP | 高 |
| **qq** | ~300 | napcat/websocket | ❌ 无官方 SDK | 高 |
| **whatsapp** | via bridge/*.ts | Baileys (TS) | bridge 保持不变（Node.js 进程） | 低 |
| **matrix** | ~400 | matrix-nio | matrix-java-sdk | 中 |
| **dingtalk** (钉钉) | ~300 | dingtalk SDK | dingtalk SDK (Java) | 低 |
| **wecom** (企业微信) | ~250 | wecom SDK | wecom SDK (Java) | 低 |
| **msteams** | ~300 | teams SDK | Microsoft Graph SDK | 中 |
| **email** | ~201 | imap + smtp | Jakarta Mail | 低 |
| **mochat** | ~100 | HTTP webhook | Spring HTTP | 低 |

## 渠道实现模板

所有渠道遵循同一模式。以 Telegram 为例：

```java
// TelegramChannel.java
@Component
public class TelegramChannel extends BaseChannel {
    private final TelegramBotsApi botsApi;
    private TelegramLongPollingBot bot;

    public TelegramChannel(MessageBus bus, NanobotProperties config) {
        super("telegram", "Telegram", bus, config);
        this.botsApi = new TelegramBotsApi();
    }

    // === 对标 Python start() ===
    @Override
    public void start() {
        var channelConfig = config.channels().custom().get("telegram");
        String token = System.getenv("TELEGRAM_BOT_TOKEN");
        if (token == null) {
            logger.warn("TELEGRAM_BOT_TOKEN not set, skipping");
            return;
        }

        bot = new TelegramLongPollingBot(token) {
            @Override
            public void onUpdateReceived(Update update) {
                if (!update.hasMessage() || !update.getMessage().hasText())
                    return;
                var msg = update.getMessage();
                var inbound = new InboundMessage(
                    "telegram",
                    String.valueOf(msg.getChatId()),        // sender_id
                    String.valueOf(msg.getChatId()),        // chat_id
                    msg.getText(),                           // content
                    Instant.ofEpochSecond(msg.getDate()),
                    List.of(),  // media URLs (可扩展)
                    Map.of("message_id", msg.getMessageId()),
                    null
                );
                try {
                    bus.publishInbound(inbound);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            @Override
            public String getBotUsername() { return botUsername; }
        };

        try {
            botsApi.registerBot(bot);
        } catch (TelegramApiException e) {
            throw new RuntimeException("Failed to register bot", e);
        }
        running = true;
        logger.info("Telegram channel started");
    }

    // === 对标 Python send() ===
    @Override
    public void send(OutboundMessage msg) throws Exception {
        var chatId = Long.parseLong(msg.chatId());
        var sendMsg = SendMessage.builder()
            .chatId(chatId)
            .text(msg.content())
            .build();
        bot.execute(sendMsg);
    }

    @Override
    public void stop() {
        running = false;
        // botsApi 自动管理 lifecycle
    }
}
```

## 渠道开发清单（按优先级）

### 第一批 — 与 P6 同时（验证 channel 框架）
- [x] **WebSocket** — P6 已完成
- [x] **Console** — P6 已完成

### 第二批 — 最常用
- [ ] **Telegram** — TelegramBots Java SDK，对接最简单
- [ ] **Discord** — JDA，最活跃的 Java Discord library
- [ ] **Slack** — com.slack.api:slack-api-client，Slack 官方支持

### 第三批 — 国内平台
- [ ] **Feishu (飞书)** — com.larksuite.oapi:lark-oapi
- [ ] **DingTalk (钉钉)** — 钉钉开放平台 SDK
- [ ] **WeCom (企业微信)** — 企业微信 Java SDK

### 第四批 — 特殊处理
- [ ] **WhatsApp** — bridge 保持 TypeScript！Java 端只需启动 Node.js 子进程并接管其 WebSocket
- [ ] **WeChat (微信)** — 无官方 SDK，用 itchat4j（社区库）或考虑保留 Python 端 bridge 模式
- [ ] **QQ** — 同上，可能需通过 napcat/llonebot WebSocket 协议

## WhatsApp Bridge 集成（保持 TypeScript）

```java
// WhatsAppBridgeManager.java
@Component
public class WhatsAppBridgeManager {
    private Process bridgeProcess;

    @PostConstruct
    public void startBridge() {
        // 对标 Python: 启动 Node.js bridge 进程
        var bridgeDir = Path.of("bridge");
        var processBuilder = new ProcessBuilder(
            "bun", "run", "index.ts"
        ).directory(bridgeDir.toFile())
         .inheritIO();

        bridgeProcess = processBuilder.start();

        // bridge 通过 WebSocket 连接到 Java 后端
        // 连接建立后 → 视作 WebSocket Channel
    }

    @PreDestroy
    public void stopBridge() {
        if (bridgeProcess != null && bridgeProcess.isAlive()) {
            bridgeProcess.destroy();
        }
    }
}
```

## 测试对齐

每个 channel 需要：

1. **单元测试**: mock 平台 SDK → 验证 InboundMessage 构建正确
2. **集成测试**: 用录制/回放或 sandbox token 验证真实 API

```java
// TelegramChannelTest.java
class TelegramChannelTest {
    @Test void messageConvertsToInbound() {
        var channel = new TelegramChannel(mockBus, config);
        var mockUpdate = mockTelegramMessage(123L, "Hello");
        channel.onUpdateReceived(mockUpdate);
        verify(mockBus).publishInbound(argThat(msg ->
            msg.channel().equals("telegram")
            && msg.content().equals("Hello")
            && msg.chatId().equals("123")
        ));
    }

    @Test void outboundSendsToTelegram() throws Exception {
        var channel = new TelegramChannel(mockBus, config);
        channel.bot = mockBot;
        var out = new OutboundMessage("telegram", "123", "Hi!", ...);
        channel.send(out);
        verify(mockBot).execute(argThat(sendMsg ->
            sendMsg.getText().equals("Hi!")
        ));
    }
}
```

## 渠道注册机制

对标 Python `ChannelRegistry`，所有 `@Component` channel 自动注入：

```java
// ChannelAutoConfiguration.java
@Configuration
public class ChannelAutoConfiguration {
    @Bean
    public ChannelManager channelManager(
            MessageBus bus, NanobotProperties config,
            List<BaseChannel> channels) {  // Spring 自动注入所有 BaseChannel Bean
        var manager = new ChannelManager(bus, config);
        for (var ch : channels) {
            // 按 config 决定是否启用
            manager.register(ch);
        }
        return manager;
    }
}
```

每个 channel 根据配置决定是否启动：

```java
public abstract class BaseChannel {
    /** 对标 Python: config 中是否有该 channel 的 API key */
    public boolean isConfigured() {
        var envKey = name.toUpperCase() + "_BOT_TOKEN";
        return System.getenv(envKey) != null;
    }
}
```

## 验证标准

```bash
# 单个 channel 验证
TELEGRAM_BOT_TOKEN=xxx mvn spring-boot:run
# 发消息到你的 bot
# 预期: 收到 agent 回复

# 多 channel 并行
TELEGRAM_BOT_TOKEN=xxx DISCORD_BOT_TOKEN=yyy mvn spring-boot:run
# Telegram 和 Discord 同时工作，互不干扰
```

## 代码量估算（每个 channel）

- 简单 channel（Email、Webhook 类）: ~150 行
- 中等 channel（Telegram、Slack、Discord）: ~250 行
- 复杂 channel（飞书、微信）: ~400 行
- Channel 注册/管理公共代码: ~100 行
- **首批 5 个 channel 合计: ~1,500 行**

## 全模块代码量汇总

| Phase | 模块 | 估算行数 |
|-------|------|---------|
| P0 | 项目骨架 + Config | ~750 |
| P1 | MessageBus | ~170 |
| P2 | Provider Layer | ~1,920 |
| P3 | Tool Layer | ~695 |
| P4 | Agent Loop + Runner | ~905 |
| P5 | Session + Memory | ~860 |
| P6 | Channel + WebUI | ~660 |
| P7 | 补渠道 (×5) | ~1,500 |
| **合计** | | **~7,460 行 Java** |

对比 Python 核心（`nanobot/agent/` + `nanobot/providers/` + `nanobot/bus/` + `nanobot/session/` + 1 个 channel）约 ~23,000 行。7,460 行 Java 覆盖相同功能范围，得益于 Java 语言 + Spring Boot framework 对 boilerplate 的消除（Records vs @dataclass + `__init__`、Spring Bean vs 手动 DI、Jackson vs json_repair 等）。

加上测试（~2,000 行）和注释/空行，最终项目约 **10,000-12,000 行**。
