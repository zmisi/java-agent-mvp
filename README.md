# java-agent-mvp

PostgreSQL 只读查询 Agent（Spring AI + DashScope Qwen + MCP），带 **Web UI** 与 **会话持久化**（本机 PostgreSQL `agent_ui` schema）。

## 免责声明

本项目**仅供学习交流**使用。未经项目维护者书面授权，不得将本项目（含源代码、文档、示例数据及衍生成果）用于任何**商业用途**。

## 容器一键运行（Docker / Podman）

无需本机安装 Java、PostgreSQL、Node。项目提供 `Dockerfile` 与 `docker-compose.yml`，**Docker** 与 **Podman**（含 [Podman Desktop](https://podman-desktop.io/)）均可使用，命令将 `docker` 换成 `podman` 即可。

需要阿里云 **DashScope API Key**。

### Docker

安装 [Docker](https://docs.docker.com/get-docker/)（含 Docker Compose）后，与 **admission-score-mcp**、**data-collection** 同级 checkout：

```text
git/
├── java-agent-mvp/
├── data-collection/      # admissions-init 镜像来源
└── admission-score-mcp/
```

```bash
cp .env.example .env
# 编辑 .env：DASHSCOPE_API_KEY、WEB_LOGIN_SECRET、WECHAT_APP_ID、WECHAT_APP_SECRET、WECHAT_JWT_SECRET（微信小程序登录必填）

docker compose up --build
```

### 使用 Podman

1. 安装 [Podman Desktop](https://podman-desktop.io/)，在应用内完成 **Install Podman** 并启动 **Podman Machine**（状态为 Running）。
2. 终端若提示 `command not found: podman`：新开一个终端窗口，或执行 `eval "$(/usr/libexec/path_helper -s)"`；仍不行则确认 `/opt/podman/bin/podman` 存在，并把 `export PATH="/opt/podman/bin:$PATH"` 写入 `~/.zshrc`。
3. 在项目目录执行（**推荐用项目脚本**，避免误用 Docker Desktop 的 `docker-compose` / `docker-credential-desktop`）：

```bash
cp .env.example .env
# 编辑 .env：DASHSCOPE_API_KEY、WEB_LOGIN_SECRET、WECHAT_APP_ID、WECHAT_APP_SECRET、WECHAT_JWT_SECRET

./scripts/fix-podman-env.sh          # 首次使用 Podman 时执行一次
./scripts/podman-compose.sh up --build
```

`podman machine start` 若提示 `already running` 可忽略。重建 DB 与镜像：`./scripts/podman-compose.sh down -v --rmi local && ./scripts/podman-compose.sh up --build`

常用对应关系：`docker compose` → `./scripts/podman-compose.sh`，例如 `./scripts/podman-compose.sh exec postgres psql -U agent -d employees`。

## 微信小程序 API 安全

推荐链路（已实现）：

1. 小程序 `wx.login()` 取得 `code`，仅服务端调用微信 `jscode2session` 换 `openid`（不信任客户端身份）。
2. 服务端签发 **JWT（HS256）+ 服务端会话**（`auth_session`），小程序后续请求带 `Authorization: Bearer <token>`。
3. **路径级 RBAC**：`guest` / `member` 仅可访问 `/api/auth/*`、`/api/conversations/*`、`/api/admission/*`；其余 `/api/*` 管理接口（如 RAG 文档管理、用户管理）需 `admin`。
4. **会话归属**：`conversation.user_id` 绑定微信用户，禁止跨用户读写会话。
5. **登录限流**、**JWT jti 与会话绑定**、**启动时校验** `WECHAT_JWT_SECRET` 长度（配置 `WECHAT_APP_ID` 时）。

小程序端请勿把 token 写入日志；生产环境务必 HTTPS，并轮换 `WECHAT_JWT_SECRET`。

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
| `postgres` | PostgreSQL 16，库 `employees`，用户/密码 `agent`/`agent`；**本机**连 `127.0.0.1:5431`（容器内仍为 5432） |
| `admissions-init` | 一次性任务：build **data-collection** 镜像，migrate + 导入 HFUT 招生数据到 **`admissions`** schema |
| `app` | Spring Boot（`docker` profile），内置 Node + Postgres MCP + admission-score MCP |

首次启动时 `docker/postgres/init/` 会创建 **`emp`** schema 及与 [employees 示例库](https://github.com/datacharmer/test_db) 一致的 6 张表（`employee`、`department` 等），并导入约 500 名员工的子集；Flyway 在 **`agent_ui`** schema 建会话表；**`admissions`** 由 `admissions-init` 写入（非 Flyway、非 initdb.d）。若需与本地全量数据一致，可运行 `./scripts/generate-docker-emp-seed.sh` 后执行 `compose down -v && compose up`（Docker 或 Podman 均可）。

单独重导招生数据：`docker compose run --rm admissions-init`（Podman 用 `./scripts/podman-compose.sh run --rm admissions-init`）。

停止并删除数据卷：`docker compose down -v` 或 `podman compose down -v`

**MCP 启动失败**（日志里 Node `v12`、`Unexpected token '.'`、或 `TimeoutException`）：镜像需 Node ≥16，请重新构建应用镜像：`docker compose build --no-cache app && docker compose up`（Podman 同理将 `docker` 改为 `podman`）。

本机开发仍可用下方「运行（Web UI）」；容器环境使用 `application-docker.yml` 与 `mcp-servers-config.docker.json`，不影响本机 `mcp-servers-config.json`。

## 运行（Web UI，默认）

1. 确保本机 PostgreSQL 可连接，且 `mcp-servers-config.json` 里的连接与 `application.yml` 的 `spring.datasource` 指向同一实例即可（库名默认 `employees`）。
2. **（本机开发，结构化招生分数/计划）** 在 **data-collection** 仓库执行 `python scripts/migrate_db.py` 与 `python scripts/import_hfut_to_db.py`。**Docker Compose 已自动执行**（`admissions-init` 服务）；本机手动步骤仅在不使用容器时需要。
3. **admission-score MCP**（与 `java-agent-mvp` 同级目录 `admission-score-mcp`）：
   ```bash
   cd ../admission-score-mcp && npm install && npm run build
   ```
   Chat 会加载 `admission-score` MCP（工具 `getMajorByScore`）。脚本路径见 `mcp-servers-config.json`（默认 `../admission-score-mcp/dist/index.js`）。
4. 设置 `DASHSCOPE_API_KEY`；设置 `WEB_LOGIN_SECRET`（Web Console 登录）；如数据库需要密码，设置 `AGENT_UI_DB_USER` / `AGENT_UI_DB_PASSWORD`。
5. 启动：

```bash
export DASHSCOPE_API_KEY=...
export WEB_LOGIN_SECRET=...          # Web Console 登录
# export AGENT_UI_DB_USER=...
# export AGENT_UI_DB_PASSWORD=...    # 如需要
mvn clean spring-boot:run
```

6. 浏览器打开 `http://localhost:8080/`  
   - Web Console 为 **纯 Chat**：左侧会话列表，**New Chat** 创建会话；点选会话查看历史。  
   - 首次访问需输入 **`WEB_LOGIN_SECRET`**（与 `application.yml` 中 `app.wechat.web-login-secret` 一致；本地见 `.env.example`）。  
   - 消息与模型侧窗口一致写入 `agent_ui.chat_memory_message`（含 tool 调用/结果 JSON）。  
   - 主 Chat 已集成 RAG：可问数据库，也可问内置 Markdown 知识库（如 `RAG 和微调有什么区别？`）。

Flyway 会在 **`agent_ui`** schema 下创建会话相关表，与业务 `emp` / `admissions` 等 schema 隔离。历史版本曾使用 **`db_agent`** schema（DB Release / DB Provisioning）；`V16__drop_db_agent.sql` 会删除该 schema 及其表。

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

路由规则在 `application.yml` 的 `app.rag.routing.database-patterns` / `rag-patterns` 中配置（Java 正则，不区分大小写）：**分数查专业**命中 `database-patterns` 后跳过 RAG，由模型调用 `getMajorByScore`；**招生简章/政策**命中 `rag-patterns` 后走知识库检索。

多校招生简章/政策类问题（未点名学校）会按 `app.rag.admissions.schools` 对每所大学分别检索并合并；回答格式由 `app.rag.admissions.answer-format-template` 约束。

后端 RAG 日志步骤标签（`RagFlowLogStep`）：`[question]` → `[retrieve]` →（多校时）`[format]` → `[prompt]` → `[answer]`。

这是教学版实现，没有上传文件、爬虫或持久化向量库。后续升级路线是：先把 `SimpleVectorStore` 换成 pgvector，再增加文档上传和后台索引任务。

## CLI 模式（可选）

仍保留原来的终端交互，需显式 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=cli
```

CLI 会话 ID 形如 `cli-<uuid>`，同样写入 `agent_ui`（启动时自动插入 `conversation` 行）。

## Flyway（本地开发）

若启动报 `Migration checksum mismatch for migration version N`，说明 `VN__*.sql` 在**已执行后被修改过**。在**开发库**可修复 Flyway 元数据（不重复执行 SQL）：

```bash
# 本机直连 Postgres（默认 5432）
mvn flyway:repair -Dflyway.user="$AGENT_UI_DB_USER"

# Docker Compose（postgres 映射到宿主机 5431，用户/密码见 docker-compose.yml）
AGENT_UI_DB_USER=agent AGENT_UI_DB_PASSWORD=agent \
  mvn flyway:repair -Dflyway.url=jdbc:postgresql://127.0.0.1:5431/employees
```

（密码使用环境变量 `AGENT_UI_DB_PASSWORD`。）之后**不要改**已应用的迁移文件，新变更请新增更高版本号，例如 `V17__*.sql`。

删除大量 Java / MyBatis 资源后若出现 `ClassNotFoundException` 指向已删 mapper，先执行 **`mvn clean`** 再启动，避免 `target/classes/mapper/` 残留旧 XML。

## 配置摘要

| 配置 | 说明 |
|------|------|
| `spring.datasource.*` | 会话库 JDBC（默认 `jdbc:postgresql://127.0.0.1:5432/employees`） |
| `AGENT_UI_DB_USER` / `AGENT_UI_DB_PASSWORD` | 数据库账号与密码 |
| `WEB_LOGIN_SECRET` | Web Console 登录密钥（`app.wechat.web-login-secret`，至少 32 字符） |
| `DASHSCOPE_API_KEY` | 阿里云 DashScope API Key（Chat / Embedding） |
| `app.db-agent.chat-target` | Chat 使用的 MCP 目标名（默认 `chat-readonly`） |
| `app.db-agent.targets.*` | MCP 目标定义（默认 `chat-readonly` → `admission-score`） |
| `app.agent.prompt.location` | System prompt 文件（默认 `classpath:prompts/db-agent-system.md`） |
| `app.agent.prompt.schema` | Prompt 中 `{schema}` 占位符（默认 `emp`） |
| `app.chat.memory.max-messages` | 每会话载入模型的最近消息条数（滑动窗口，默认 30） |
| `app.chat.context-window.max-input-tokens` | 声明的模型输入 token 上限（用于 UI 占用百分比；启发式计量，默认 16384） |
| `spring.ai.mcp.client.stdio.servers-configuration` | Chat MCP 进程配置（`admission-score` 等） |

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
- `POST /api/conversations/{id}/archive` — 归档会话（从默认列表隐藏，消息保留）  
- `DELETE /api/conversations/{id}` — 删除会话及消息  

Web UI 支持：左侧会话 `…` 菜单（重命名 / 删除）、左下角**设置**（深色 / 浅色 / 跟随系统、字体大小，保存在浏览器 `localStorage`）、输入框左侧 **Context** 圆环（最后一次发送后估计的输入上下文占用与分类分解）。

## Workflow API（admission-workflow）

显式 Workflow Runtime，执行「志愿分析报告」链路，每步写入 DB checkpoint（`agent_ui.workflow_run` / `workflow_checkpoint`）。开关：`app.admission-workflow.enabled=true`。

**Week 2 异步（默认）：** `POST /api/workflows/report` 立即返回 `202 Accepted` + `{ runId, status: "PENDING" }`，同 JVM Worker 从 Redis 队列消费并执行；客户端轮询 `GET /api/workflows/{runId}`，完成后 `GET /api/workflows/{runId}/report` 取完整报告。调试或单测可用 `?sync=true` 或请求头 `X-Workflow-Sync: true` 走同步 `200` 路径（与 Week 1 行为一致）。

架构：`API → Redis List (LPUSH/BRPOP) → WorkflowJobConsumer → WorkflowEngine → Postgres checkpoints`

配置（`application.yml` / `application-docker.yml`）：

```yaml
app.admission-workflow.async:
  enabled: false          # 本地/CI 无 Redis 时 false；docker profile 为 true
  queue-key: admission-workflow:jobs
  consumer-enabled: true
  brpop-timeout-seconds: 5
```

`docker compose up` 会启动 `redis:7`；app 依赖 Redis healthcheck。

节点链：`compile_query → score_tool → preference_rag → filter_score_majors → policy_rag → verify_answer → format_response → synthesize_report`

`score_tool` 与 Chat MCP 一致：用 **用户分 + 15** 调用 `getMajorByScore`（MCP 返回 `min_score <= 查询分`），再在 `filter_score_majors` 按用户真实分划分冲（+15 内）/ 稳（至用户分）/ 保（-15 及以下）。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/workflows/report` | 默认异步：`202` + `{ runId, status: "PENDING" }`；`?sync=true` 同步返回完整 `WorkflowReportResponse` |
| GET | `/api/workflows/{runId}` | 查询 run 状态、checkpoint 摘要、`progress: { completedNodes, totalNodes }` |
| GET | `/api/workflows/{runId}/report` | 终态（`SUCCEEDED`/`FAILED`）时返回完整 report DTO；`RUNNING`/`PENDING` → `409` |
| GET | `/api/workflows/{runId}/checkpoints` | 查询全部 checkpoint（含 input/output JSON，用于演示 resume） |

异步轮询示例：

```bash
# 1. 入队
curl -i -X POST http://localhost:8080/api/workflows/report \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"message":"安徽物理类620分，合工大计算机和软件工程政策"}'
# HTTP/1.1 202  {"runId":"...","status":"PENDING"}

# 2. 轮询状态
curl http://localhost:8080/api/workflows/<runId> -H "Authorization: Bearer <token>"

# 3. 完成后取报告
curl http://localhost:8080/api/workflows/<runId>/report -H "Authorization: Bearer <token>"
```

同步调试：

```bash
curl -X POST 'http://localhost:8080/api/workflows/report?sync=true' \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"message":"安徽物理类620分"}'
```

微信小程序用户（guest/member）可访问 `/api/workflows/*`；需 JWT 鉴权。

Web UI 与微信小程序 Composer 均提供 **「志愿报告」** 按钮：默认异步入队并轮询，完成后展示结构化报告（表格 + 政策来源 + LLM 叙述）；普通发送仍走 `/api/conversations/{id}/chat`。

## Observability（Week 4）

Micrometer + OpenTelemetry trace + Prometheus metrics。本地可选启用 Jaeger / Prometheus：

```bash
# 启动应用 + Redis + Postgres + Jaeger + Prometheus
docker compose --profile observability up --build

# Podman：勿用裸命令 podman compose（会走 Docker Desktop 的 compose 插件）
./scripts/podman-compose.sh --profile observability up --build

# 或本机开发（online profile 已包含 observability）
SPRING_PROFILES_ACTIVE=online mvn spring-boot:run

# 本机 mvn + 容器化 Jaeger/Prometheus（勿与 docker app 同时占 8080）
./scripts/podman-compose.sh --profile observability up jaeger prometheus -d
```

| 组件 | URL |
|------|-----|
| Jaeger UI | http://localhost:16686 |
| Prometheus | http://localhost:9090 |
| App metrics | http://localhost:8080/actuator/prometheus |

一次 workflow 请求在 Jaeger 中可看到：`agent.workflow.run` → `agent.workflow.node.*` → `agent.rag.retrieve` / `agent.tool.getMajorByScore` / `agent.llm.synthesize`。

关键指标（Prometheus）：

- `agent_workflow_run_total{status="SUCCEEDED"}`
- `agent_workflow_node_seconds_bucket{node="score_tool"}`
- `agent_rag_retrieve_hits`
- `agent_tool_call_seconds_count{tool="getMajorByScore"}`

HTTP 响应头含 `X-Trace-Id`（当 tracing 启用时）。日志 pattern 含 `traceId` / `workflowRunId`（`application-observability.yml`）。

## Eval golden set（Week 4）

见 [eval/README.md](eval/README.md)。

```bash
# CI / 日常：intent + deterministic workflow（mock MCP/RAG）
mvn test

# 本地全链路（需 Postgres + MCP + 配置）
EVAL_LIVE=1 mvn test -Peval-live
```

Live 报告输出：`eval/reports/latest.md`。

### NL → IR 编译器（admission-compiler）

复杂约束解析（如「长三角、不当老师、央国企」）见 [admission-compiler/README.md](admission-compiler/README.md)。

```bash
cd admission-compiler && python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python eval/run_eval.py    # 9/9 golden cases，无需 API Key
```

## 故障排查

- **MyBatis 启动失败、指向已删除的 `*Mapper.xml`**：源码已删但 `target/classes/mapper/` 仍有旧文件。执行 `mvn clean spring-boot:run`（或 `mvn clean package` 后再启动）。
- **`POST /api/conversations` 返回 500**：看控制台或日志里 Flyway 是否成功；若出现 `relation "agent_ui.conversation" does not exist`，说明迁移未执行或连到了别的库。确认 `spring.datasource.url` 与 `spring.flyway.locations`（默认 `classpath:db/migration`）及库权限。重启后若仍失败，接口在数据库异常时会返回 JSON：`{"error":"database_error","message":"...根因..."}`（由 `RestApiExceptionHandler` 提供）。
- **浏览器**：已改为仅在带 body 的请求上设置 `Content-Type: application/json`，避免无 body 的 POST 被误解析。

## 说明

- 静态页：`src/main/resources/static/`（`index.html`、`app.js`、`styles.css`）— **仅 Chat 工作区**，不含 DB Release / DB Provisioning。  
- 模型侧记忆实现：`PostgresChatMemory`（`ChatMemory`），与 `MessageChatMemoryAdvisor` 配合。`ChatContextUsageAdvisor` 在发往模型前对最终 `Prompt` 做 **启发式 token 分解**（`contextUsage`），便于观察系统 / 工具定义 / 历史对话 / 当前用户消息各占多少；非官方 tokenizer 精度。
- 持久化层：**MyBatis**（SQL 在 `src/main/resources/mapper/*.xml`，Java 仅保留 `*Mapper` 接口与 Repository 编排）。  
- Chat MCP 路由：`DbAgentTargetRegistry` 按 `app.db-agent.targets` 过滤 Spring AI 注册的 MCP client（与 `mcp-servers-config.json` 配合）。  
- 若同时引入 `spring-boot-starter-web` 与 `spring-boot-starter-webflux`，默认使用 **Servlet** 栈提供页面与 REST。
