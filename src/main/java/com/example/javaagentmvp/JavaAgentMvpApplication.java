package com.example.javaagentmvp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@SpringBootApplication
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class JavaAgentMvpApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaAgentMvpApplication.class, args);
    }

    @Bean
    QwenApiLoggingAdvisor qwenApiLoggingAdvisor() {
        return new QwenApiLoggingAdvisor();
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryProperties properties) {
        return MessageWindowChatMemory.builder()
                .maxMessages(properties.maxMessages())
                .build();
    }

    @Bean
    CommandLineRunner chatLoop(
            ChatClient.Builder chatClientBuilder,
            List<McpSyncClient> mcpClients,
            ChatMemory chatMemory,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor) {
        return args -> {
            List<ToolCallback> toolCallbacks = SyncMcpToolCallbackProvider.syncToolCallbacks(mcpClients);

            ChatClient chatClient = chatClientBuilder
                    .defaultSystem("""
                            你是 PostgreSQL 只读查询助手（schema: public）。用 MCP 工具执行 SQL 并英文回答。

                            规则：
                            1. 不要假设存在 id 列；写入 SQL 前先查列名：
                               SELECT column_name, data_type
                               FROM information_schema.columns
                               WHERE table_schema = 'public' AND table_name = '<表名>'
                               ORDER BY ordinal_position;
                            2. “最近 N 条”用 createtime 或 lastmodifiedtime DESC 排序（先确认列存在）。
                            3. 只执行 SELECT；不要 INSERT/UPDATE/DELETE/DDL。
                            4. 工具报错时根据报错修正 SQL 后重试，最多 2 次。
                            5. 最终回答中保留关键事实（如 changeid、buildid、使用的 SQL 摘要），便于用户追问。
                            6. 已知表结构：
                               - dbsbuild: buildid, changeid, description, createtime, lastmodifiedtime, buildtype
                               - opschange: changeid, type, scheduledtime, status, externalcode, createor, opowner, createtime, lastmodifiedtime
                            """)
                    .defaultToolCallbacks(toolCallbacks)
                    .defaultAdvisors(
                            MessageChatMemoryAdvisor.builder(chatMemory).build(),
                            qwenApiLoggingAdvisor)
                    .build();

            AtomicReference<String> conversationId = new AtomicReference<>("cli-" + UUID.randomUUID());

            Scanner scanner = new Scanner(System.in);
            System.out.println("🤖 Java Agent MVP 已启动！支持多轮对话（输入 exit 退出）：");
            System.out.println("   命令: /clear 清空当前会话记忆 | /new 开始新会话 | /help 帮助");
            System.out.println("   会话 ID: " + conversationId.get());
            System.out.println("已加载 MCP 工具: " +
                    toolCallbacks.stream()
                            .map(tc -> tc.getToolDefinition().name())
                            .toList());
            System.out.println();

            while (true) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(input)) {
                    break;
                }
                if (handleMetaCommand(input, chatMemory, conversationId)) {
                    continue;
                }

                try {
                    qwenApiLoggingAdvisor.resetSessionRound();
                    String response = chatClient.prompt()
                            .user(input)
                            .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId.get()))
                            .call()
                            .content();
                    System.out.println("\n🤖 " + response + "\n");
                } catch (Exception e) {
                    String message = e.getMessage();
                    if (message != null && message.contains("url error")) {
                        System.err.println("❌ DashScope 配置错误：模型名或 base-url 不正确。");
                        System.err.println("   请检查 application.yml 中 spring.ai.dashscope.chat.options.model（推荐 qwen-plus）");
                    } else if (message != null && message.contains("does not exist")) {
                        System.err.println("❌ SQL 执行失败: " + message);
                        System.err.println("   模型可能使用了不存在的列名，请重试或指定要查询的字段。");
                    } else {
                        System.err.println("❌ 错误: " + message);
                    }
                }
            }

            System.out.println("再见！");
        };
    }

    private static boolean handleMetaCommand(
            String input,
            ChatMemory chatMemory,
            AtomicReference<String> conversationId) {
        if ("/help".equalsIgnoreCase(input)) {
            System.out.println("""
                    命令:
                      /clear  清空当前会话的对话记忆
                      /new    开始新会话（新 conversationId）
                      /help   显示此帮助
                      exit    退出
                    """);
            return true;
        }
        if ("/clear".equalsIgnoreCase(input)) {
            chatMemory.clear(conversationId.get());
            System.out.println("✓ 已清空当前会话记忆。");
            return true;
        }
        if ("/new".equalsIgnoreCase(input)) {
            String previousId = conversationId.get();
            String newId = "cli-" + UUID.randomUUID();
            conversationId.set(newId);
            System.out.println("✓ 已开始新会话。");
            System.out.println("  旧会话: " + previousId);
            System.out.println("  新会话: " + newId);
            return true;
        }
        return false;
    }
}


