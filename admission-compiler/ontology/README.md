# Ontology (canonical source)

YAML files live in the Java repo at:

`src/main/resources/admission-ontology/`

Local development and pytest load that directory automatically (`ontology.py`).

Docker builds copy the same files into `./ontology` inside the image (see repo-root `docker-compose.yml`).

## Files

| File | Purpose |
|------|---------|
| `supported_capabilities.yaml` | **可执行**能力白名单（任务、槽位、确定性筛选）；注入编译/合成 prompt |
| `major_category_filters.yaml` | **已支持**专业大类（工科/理科/文科/医学/经管）；写入 `filters.include_major_discipline_groups` |
| `unsupported_signals.yaml` | **暂不支持**约束短语（保研率、就业薪资、QS排名等）；触发 `unsupported_constraints` + 用户提示 |
| `regions.yaml` | 区域 → 省份展开 |
| `exclusions.yaml` | 排除院校/专业关键词（可执行） |
| `preferences.yaml` | 预留；软偏好已迁至 `unsupported_signals`（Chat 路径无结构化数据） |

## 行为约定

1. **在白名单内** → 按 IR 确定性执行（MCP 查分/位次、Java 关键词过滤、冲稳保分档）。
2. **命中 unsupported_signals** → 写入 `unsupported_constraints`，回复说明「暂不支持，需求已记录」，**禁止**假装已筛选或编造数据。
3. **两者都不在** → 模型应回答「当前无法按该条件查询」，勿幻觉。

新增需求：优先加 `unsupported_signals` 短语；有数据后再迁入 `supported_capabilities` + 过滤实现。
