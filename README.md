# java-agent-mvp

PostgreSQL 只读查询 CLI Agent（Spring AI + DashScope Qwen + MCP）。

## 多轮对话

使用 `MessageChatMemoryAdvisor` 保留会话内 USER/ASSISTANT 历史，支持追问。

| 命令 | 说明 |
|------|------|
| `/clear` | 清空当前会话记忆 |
| `/new` | 开始新会话（新 conversationId） |
| `/help` | 显示命令帮助 |
| `exit` | 退出 |

配置 `app.chat.memory.max-messages`（默认 30）控制记忆窗口大小。

