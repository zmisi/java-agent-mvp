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
   - 主 Chat 已集成 RAG：可问数据库，也可问内置 Markdown 知识库（如 `RAG 和微调有什么区别？`）。

Flyway 会在 **`agent_ui`** schema 下创建 `conversation` 与 `chat_memory_message` 表，与业务 `public` 表隔离。

## RAG（已集成到 Chat）

主 Chat（`POST /api/conversations/{id}/chat`）在每次提问时会：

1. 从 `src/main/resources/rag-docs/**/*.md` 检索相关片段（`SimpleVectorStore` + DashScope `text-embedding-v4`）。招生数据按学校分目录，例如 `rag-docs/hfut/`、`rag-docs/hfuu/`
2. 通过 `QuestionAnswerAdvisor` 把检索上下文注入模型 Prompt
3. 与原有 DB Agent（MCP 工具 + 会话记忆）共用同一个 `ChatClient`

响应除 `assistant` 外还会返回 `sources`（命中的文档片段），Web UI 会在回答下方展示 Sources。

示例问题：

```text
RAG 和微调有什么区别？
这个 demo 用了什么向量库？
每个部门有多少员工？
```

关闭 RAG：`app.rag.enabled=false`（恢复纯 DB Agent 行为）。

路由规则（走 RAG 还是走 MCP 查库）在 `application.yml` 的 `app.rag.routing.rag-patterns` / `database-patterns` 中配置（Java 正则，不区分大小写），改配置即可扩展，无需改 Java。

招生类问题（含分数/专业等关键词且未点名学校）会按 `app.rag.admissions.schools` 对每所大学分别检索并合并；回答格式由 `app.rag.admissions.answer-format-template` 约束（按学校分组输出）。

后端 RAG 日志步骤标签（`RagFlowLogStep`）：`[question]` → `[retrieve]` →（多校时）`[format]` → `[prompt]` → `[answer]`。

这是教学版实现，没有上传文件、爬虫或持久化向量库。后续升级路线是：先把 `SimpleVectorStore` 换成 pgvector，再增加文档上传和后台索引任务。

## CLI 模式（可选）

仍保留原来的终端交互，需显式 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=cli
```

CLI 会话 ID 形如 `cli-<uuid>`，同样写入 `agent_ui`（启动时自动插入 `conversation` 行）。

## DB Provisioning（Create PG DB）

侧边栏 **New DB Provisioning** 可在指定 Linux 主机上通过 MCP 异步安装 PostgreSQL 18、建库/schema 与扩展。Spring **不直连 SSH**，仅调用独立 stdio MCP 进程 **java-agent-mcp**。

### Flyway（本地开发）

若启动报 `Migration checksum mismatch for migration version 3`，说明 `V3__db_provisioning.sql` 在已执行后被修改过。在**开发库**可修复元数据：

```bash
mvn flyway:repair -Dflyway.user="$AGENT_UI_DB_USER"
```

（密码使用环境变量 `AGENT_UI_DB_PASSWORD`，与 `application.yml` 一致。）之后不要再改已应用的 `V3` 文件，新变更请新增 `V4__*.sql`。

若曾跑过早期草稿版 V3（列名如 `server_target`），请确保已执行 `V4__provisioning_schema_align.sql`（`mvn flyway:migrate`）。

### 准备 java-agent-mcp

```bash
cd java-agent-mcp
npm install
chmod +x bin/java-agent-mcp.js
```

可选环境变量：

| 变量 | 说明 |
|------|------|
| `JAVA_AGENT_MCP_COMMAND` | 默认 `node`；若设为不存在的路径（如 `/usr/local/bin/java-agent-mcp`）会自动回退到 bundled 脚本 |
| `JAVA_AGENT_MCP_SCRIPT` | 默认 `./java-agent-mcp/bin/java-agent-mcp.js` |

若 shell 里曾 `export JAVA_AGENT_MCP_COMMAND=/usr/local/bin/java-agent-mcp` 但未安装，请 **unset** 该变量或改为 `node`。

**支持的操作系统（PG 18）**：Ubuntu 22.04/24.04、RHEL/Rocky/Alma **8/9**。**不支持 RHEL/CentOS 7**（会使用错误的 PGDG 源导致 `PayloadIsZstd` 等错误）。

### API

- `GET /api/db-provisioning` — 任务列表  
- `GET /api/db-provisioning/{id}` — 详情与分步日志（运行中可轮询）  
- `POST /api/db-provisioning` — 提交表单并启动异步任务（SSH 凭证不落库）
- `POST /api/db-provisioning/{id}/retry` — 对 **FAILED** / **CANCELLED** 任务重做（需重新提交 SSH 密码或私钥）  

## 配置摘要

| 配置 | 说明 |
|------|------|
| `spring.datasource.*` | 会话库 JDBC（默认 `jdbc:postgresql://127.0.0.1:5432/opstream`，用户 `postgres`） |
| `app.db-agent.provisioning-mcp-command` | Provisioning MCP 启动命令（默认 `node`） |
| `app.db-agent.provisioning-mcp-args` | MCP 脚本参数（默认 `./java-agent-mcp/bin/java-agent-mcp.js`） |
| `AGENT_UI_DB_PASSWORD` | 数据库密码（可选） |
| `app.agent.prompt.location` | System prompt 文件（默认 `classpath:prompts/db-agent-system.md`） |
| `app.agent.prompt.schema` | Prompt 中 `{schema}` 占位符（默认 `public`） |
| `app.chat.memory.max-messages` | 每会话载入模型的最近消息条数（滑动窗口，默认 30） |
| `app.chat.context-window.max-input-tokens` | 声明的模型输入 token 上限（用于 UI 占用百分比；启发式计量，默认 131072） |
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
- `POST /api/conversations/{id}/chat` — body `{"message":"..."}`；响应含 `assistant`、`sources`（RAG）、`contextUsage`（可选，估计的输入上下文分类与占比）  
- `PATCH /api/conversations/{id}` — body `{"title":"..."}` 重命名会话  
- `DELETE /api/conversations/{id}` — 删除会话及消息  

Web UI 支持：左侧会话 `…` 菜单（重命名 / 删除）、左下角**设置**（深色 / 浅色 / 跟随系统、字体大小，保存在浏览器 `localStorage`）、输入框左侧 **Context** 圆环（最后一次发送后估计的输入上下文占用与分类分解）。

## 故障排查

- **`POST /api/conversations` 返回 500**：看控制台或日志里 Flyway 是否成功；若出现 `relation "agent_ui.conversation" does not exist`，说明迁移未执行或连到了别的库。确认 `spring.datasource.url` 与 `spring.flyway.locations`（默认 `classpath:db/migration`）及库权限。重启后若仍失败，接口在数据库异常时会返回 JSON：`{"error":"database_error","message":"...根因..."}`（由 `RestApiExceptionHandler` 提供）。
- **浏览器**：已改为仅在带 body 的请求上设置 `Content-Type: application/json`，避免无 body 的 POST 被误解析。

## 说明

- 静态页：`src/main/resources/static/`（`index.html`、`app.js`、`styles.css`）。  
- 模型侧记忆实现：`PostgresChatMemory`（`ChatMemory`），与 `MessageChatMemoryAdvisor` 配合。`ChatContextUsageAdvisor` 在发往模型前对最终 `Prompt` 做 **启发式 token 分解**（`contextUsage`），便于观察系统 / 工具定义 / 历史对话 / 当前用户消息各占多少；非官方 tokenizer 精度。
- 持久化层：**MyBatis**（SQL 在 `src/main/resources/mapper/*.xml`，Java 仅保留 `*Mapper` 接口与 Repository 编排）。  
- 若同时引入 `spring-boot-starter-web` 与 `spring-boot-starter-webflux`，默认使用 **Servlet** 栈提供页面与 REST。
