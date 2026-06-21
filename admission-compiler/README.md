# Admission Query Compiler (NL -> IR)

将用户自然语言编译为结构化 **AdmissionQuery IR**，供 Java Workflow / MCP / RAG 执行层消费。

## 架构

```
用户消息 + 多轮上下文
    → L1 规则抽槽 (rules.py，对齐 Java AdmissionInputParser)
    → L2 本体归一化 (`src/main/resources/admission-ontology/*.yaml`：区域、排除、软偏好)
    → L3 可选 LLM 增强 (COMPILER_USE_LLM=true)
    → AdmissionQuery JSON
```

## 快速开始

```bash
cd admission-compiler
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt

# 跑 golden cases（无需 API Key）
python eval/run_eval.py

# 单元测试
pytest tests/

# 启动 HTTP 服务
uvicorn app.main:app --app-dir . --reload --port 8090
```

### 编译示例

```bash
curl -s http://localhost:8090/compile \
  -H 'Content-Type: application/json' \
  -d '{"message":"我要报考长三角的大学，不当老师"}' | python3 -m json.tool
```

## IR 字段

| 字段 | 说明 |
|------|------|
| `task` | `search_majors` / `search_rank` / `policy_qa` / `report` / `unknown` |
| `slots` | `score`, `provinces[]`, `subject_group`, `year`, `admission_type` |
| `filters` | `exclude_school_name_contains`, `exclude_major_keywords`, … |
| `preferences` | 软约束：`employment_outlook`, `salary`, `state_owned_employability` |
| `regions` | 触发的区域短语及展开省份 |
| `needs_clarification` | 缺必填槽位时追问列表 |
| `parse_trace` | 规则/本体/LLM 命中轨迹 |

## 本体维护

编辑 **`src/main/resources/admission-ontology/`** 下 YAML（Java 与 Python compiler 共用），无需改代码：

- `regions.yaml` — 「长三角」→ 江苏/浙江/上海
- `exclusions.yaml` — 「不当老师」→ 排除师范
- `preferences.yaml` — 「央国企」→ `state_owned_employability`

Docker 构建时从该目录复制到 Python 服务镜像内的 `./ontology`。

## 可选 LLM 增强

```bash
export COMPILER_USE_LLM=true
export DASHSCOPE_API_KEY=...
export COMPILER_LLM_MODEL=qwen-plus
```

默认 **关闭** LLM，规则+本体即可通过 eval。

## 与 Java 集成

Java Workflow 通过 `IntentServiceClient` 调用 `POST /compile`：

```yaml
app:
  admission-compiler:
    enabled: true          # false 时使用 Java 本地 compiler（classpath ontology）
    base-url: http://localhost:8090
    fallback-to-local: true
```

Docker Compose 已包含 `admission-compiler` 服务，并自动配置 `app` 连接。

Workflow 节点顺序：

`compile_query` → `score_tool` → `preference_rag` → `filter_score_majors` → `policy_rag` → …

