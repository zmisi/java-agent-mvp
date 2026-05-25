# java-agent-mvp

PostgreSQL 只读查询 Agent（Spring AI + DashScope Qwen + MCP），带 **Web UI** 与 **会话持久化**（本机 PostgreSQL `agent_ui` schema）。

## 容器一键运行（Docker / Podman）

无需本机安装 Java、PostgreSQL、Node。项目提供 `Dockerfile` 与 `docker-compose.yml`，**Docker** 与 **Podman**（含 [Podman Desktop](https://podman-desktop.io/)）均可使用，命令将 `docker` 换成 `podman` 即可。

需要阿里云 **DashScope API Key**。

### Docker

安装 [Docker](https://docs.docker.com/get-docker/)（含 Docker Compose）后：

```bash
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY

docker compose up --build
```

### 使用 Podman

1. 安装 [Podman Desktop](https://podman-desktop.io/)，在应用内完成 **Install Podman** 并启动 **Podman Machine**（状态为 Running）。
2. 终端若提示 `command not found: podman`：新开一个终端窗口，或执行 `eval "$(/usr/libexec/path_helper -s)"`；仍不行则确认 `/opt/podman/bin/podman` 存在，并把 `export PATH="/opt/podman/bin:$PATH"` 写入 `~/.zshrc`。
3. 在项目目录执行（**推荐用项目脚本**，避免误用 Docker Desktop 的 `docker-compose` / `docker-credential-desktop`）：

```bash
cp .env.example .env
# 编辑 .env，填入 DASHSCOPE_API_KEY

./scripts/fix-podman-env.sh          # 首次使用 Podman 时执行一次
./scripts/podman-compose.sh up --build
```

`podman machine start` 若提示 `already running` 可忽略。重建 DB 与镜像：`./scripts/podman-compose.sh down -v --rmi local && ./scripts/podman-compose.sh up --build`

常用对应关系：`docker compose` → `./scripts/podman-compose.sh`，例如 `./scripts/podman-compose.sh exec postgres psql -U agent -d employees`。

**不要**设置 `PODMAN_COMPOSE_PROVIDER=podman`（会把 `compose up` 变成错误的 `podman up`，报 `unknown flag: --build`）。裸命令 `podman compose` 也可能走有问题的外部 provider；请用 `./scripts/podman-compose.sh`。

**`docker-credential-desktop: executable file not found`**：执行 `./scripts/fix-podman-env.sh` 去掉 `~/.docker/config.json` 里的 `"credsStore": "desktop"`，再用 `./scripts/podman-compose.sh`。

**拉镜像报 `unauthorized`（docker.io）**：

```bash
podman logout docker.io
./scripts/podman-compose.sh up --build
```

本项目基础镜像使用 [AWS Public ECR](https://gallery.ecr.aws/) 的 `public.ecr.aws/docker/library/...`，减轻对 docker.io 登录的依赖。

---

启动后打开 **http://localhost:8080/**。Compose 会拉起：

| 服务 | 说明 |
|------|------|
| `postgres` | PostgreSQL 16，库 `employees`，用户/密码 `agent`/`agent` |
| `app` | Spring Boot（`docker` profile），内置 Node + Postgres MCP |

首次启动时 `docker/postgres/init/` 会创建 **`emp`** schema 及与 [employees 示例库](https://github.com/datacharmer/test_db) 一致的 6 张表（`employee`、`department` 等），并导入约 500 名员工的子集；Flyway 在 **`agent_ui`** schema 建会话表。若需与本地全量数据一致，可运行 `./scripts/generate-docker-emp-seed.sh` 后执行 `compose down -v && compose up`（Docker 或 Podman 均可）。

停止并删除数据卷：`docker compose down -v` 或 `podman compose down -v`

**MCP 启动失败**（日志里 Node `v12`、`Unexpected token '.'`、或 `TimeoutException`）：镜像需 Node ≥16，请重新构建应用镜像：`docker compose build --no-cache app && docker compose up`（Podman 同理将 `docker` 改为 `podman`）。

本机开发仍可用下方「运行（Web UI）」；容器环境使用 `application-docker.yml` 与 `mcp-servers-config.docker.json`，不影响本机 `mcp-servers-config.json`。

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
