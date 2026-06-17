# Admission Query Compiler (NL -> IR)

将用户自然语言编译为结构化 **AdmissionQuery IR**，供 Java Workflow / MCP / RAG 执行层消费。

## 架构

```
用户消息 + 多轮上下文
    → L1 规则抽槽 (rules.py，对齐 Java AdmissionInputParser)
    → L2 本体归一化 (ontology/*.yaml：区域、排除、软偏好)
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

编辑 `ontology/` 下 YAML，无需改代码：

- `regions.yaml` — 「长三角」→ 江苏/浙江/上海
- `exclusions.yaml` — 「不当老师」→ 排除师范
- `preferences.yaml` — 「央国企」→ `state_owned_employability`

## 可选 LLM 增强

```bash
export COMPILER_USE_LLM=true
export DASHSCOPE_API_KEY=...
export COMPILER_LLM_MODEL=qwen-plus
```

默认 **关闭** LLM，规则+本体即可通过 eval。

## 与 Java 集成（下一步）

Java 侧 `POST http://intent-service:8090/compile`，将返回的 IR 交给 Planner / `FilterScoreMajorsNode` 扩展执行。

Schema：`schema/admission_query.schema.json`
