# java-agent-mvp

PostgreSQL 只读查询 Agent（Spring AI + DashScope Qwen + MCP），带 **Web UI** 与 **会话持久化**（本机 PostgreSQL `agent_ui` schema）。

## 运行（Web UI，默认）

1. 确保本机 PostgreSQL 可连接，且 `mcp-servers-config.json` 里的连接与 `application.yml` 的 `spring.datasource` 指向同一实例即可（库名默认 `opstream`）。
2. 设置 `DASHSCOPE_API_KEY`；如数据库需要密码，设置 `AGENT_UI_DB_PASSWORD`。
3. 启动：

```bash
export DASHSCOPE_API_KEY=...
# export AGENT_UI_DB_PASSWORD=...   # 如需要
mvn spring-boot:run
```

4. 浏览器打开 `http://localhost:8080/`  
   - 左侧会话列表，**新对话** 创建会话；点选会话查看历史。  
   - 消息与模型侧窗口一致写入 `agent_ui.chat_memory_message`（含 tool 调用/结果 JSON）。

Flyway 会在 **`agent_ui`** schema 下创建 `conversation` 与 `chat_memory_message` 表，与业务 `public` 表隔离。

## CLI 模式（可选）

仍保留原来的终端交互，需显式 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=cli
```

CLI 会话 ID 形如 `cli-<uuid>`，同样写入 `agent_ui`（启动时自动插入 `conversation` 行）。

## 配置摘要

| 配置 | 说明 |
|------|------|
| `spring.datasource.*` | 会话库 JDBC（默认 `jdbc:postgresql://127.0.0.1:5432/opstream`，用户 `postgres`） |
| `AGENT_UI_DB_PASSWORD` | 数据库密码（可选） |
| `app.agent.prompt.location` | System prompt 文件（默认 `classpath:prompts/db-agent-system.md`） |
| `app.agent.prompt.schema` | Prompt 中 `{schema}` 占位符（默认 `public`） |
| `app.chat.memory.max-messages` | 模型上下文窗口（默认 30 条 `Message`） |
| `spring.ai.mcp.client.stdio.servers-configuration` | MCP 配置（Postgres MCP） |

### System prompt（最佳实践）

- 正文放在 **`src/main/resources/prompts/db-agent-system.md`**，与 Java 代码分离，便于评审和迭代。
- 通过 `app.agent.prompt.location` 指向 classpath 或 `file:` 路径（例如 `file:./config/db-agent-system.md` 用于本机覆盖，无需改 jar）。
- 文件中可用 `{schema}`，由 `app.agent.prompt.schema` 在启动时替换。
- 启动时若文件不存在会 **fail-fast**，避免带着空 prompt 跑生产。

## 多轮对话与命令（CLI）

| 命令 | 说明 |
|------|------|
| `/clear` | 清空当前会话消息（保留会话行） |
| `/new` | 新会话 ID |
| `/help` | 帮助 |
| `exit` | 退出 |

## API（Web UI 使用）

- `GET /api/conversations` — 会话列表  
- `POST /api/conversations` — 新建会话  
- `GET /api/conversations/{id}/messages` — 某会话全部消息（展示用文本）  
- `POST /api/conversations/{id}/chat` — body `{"message":"..."}`  
- `PATCH /api/conversations/{id}` — body `{"title":"..."}` 重命名会话  
- `DELETE /api/conversations/{id}` — 删除会话及消息  

Web UI 支持：左侧会话 `…` 菜单（重命名 / 删除）、左下角**设置**（深色 / 浅色 / 跟随系统、字体大小，保存在浏览器 `localStorage`）。

## 故障排查

- **`POST /api/conversations` 返回 500**：看控制台或日志里 Flyway 是否成功；若出现 `relation "agent_ui.conversation" does not exist`，说明迁移未执行或连到了别的库。确认 `spring.datasource.url` 与 `spring.flyway.locations`（默认 `classpath:db/migration`）及库权限。重启后若仍失败，接口在数据库异常时会返回 JSON：`{"error":"database_error","message":"...根因..."}`（由 `RestApiExceptionHandler` 提供）。
- **浏览器**：已改为仅在带 body 的请求上设置 `Content-Type: application/json`，避免无 body 的 POST 被误解析。

## 说明

- 静态页：`src/main/resources/static/`（`index.html`、`app.js`、`styles.css`）。  
- 模型侧记忆实现：`PostgresChatMemory`（`ChatMemory`），与 `MessageChatMemoryAdvisor` 配合。
- 持久化层：**MyBatis**（SQL 在 `src/main/resources/mapper/*.xml`，Java 仅保留 `*Mapper` 接口与 Repository 编排）。  
- 若同时引入 `spring-boot-starter-web` 与 `spring-boot-starter-webflux`，默认使用 **Servlet** 栈提供页面与 REST。
