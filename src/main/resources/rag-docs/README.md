# RAG 知识库目录

按学校拆分招生数据，便于分别采集与更新。

```text
rag-docs/
├── guide/                 # 项目与 RAG 说明（非招生数据）
│   ├── java-agent-mvp-guide.md
│   ├── rag-basics.md
│   └── spring-ai-rag.md
├── hfut/                  # 合肥工业大学
│   ├── plans/{year}/{province}/
│   ├── scores/{year}/{province}/
│   └── charters/{year}/
├── hfuu/                  # 合肥大学
│   ├── plans/{year}/
│   ├── scores/{year}/
│   └── charters/{year}/
└── {school}/              # 后续学校：aust、ahau、ahut 等
    ├── plans/
    ├── scores/
    └── charters/
```

同步方式（在 `data-collection` 项目采集后）：

```bash
cp -R ../data-collection/output/markdown/hfut  src/main/resources/rag-docs/
cp -R ../data-collection/output/markdown/hfuu src/main/resources/rag-docs/
```

应用配置：`classpath:/rag-docs/**/*.md`（无需随目录调整而修改）。
