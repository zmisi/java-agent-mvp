package com.example.javaagentmvp.cli;

import com.example.javaagentmvp.QwenApiLoggingAdvisor;
import com.example.javaagentmvp.chat.AgentConversationRepository;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Scanner;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Profile("cli")
public class CliChatRunner implements CommandLineRunner {

    private final ChatClient chatClient;

    private final ChatMemory chatMemory;

    private final QwenApiLoggingAdvisor qwenApiLoggingAdvisor;

    private final AgentConversationRepository conversationRepository;

    public CliChatRunner(
            ChatClient chatClient,
            ChatMemory chatMemory,
            QwenApiLoggingAdvisor qwenApiLoggingAdvisor,
            AgentConversationRepository conversationRepository) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
        this.qwenApiLoggingAdvisor = qwenApiLoggingAdvisor;
        this.conversationRepository = conversationRepository;
    }

    @Override
    public void run(String... args) {
        AtomicReference<String> conversationId = new AtomicReference<>("cli-" + UUID.randomUUID());
        Instant now = Instant.now();
        conversationRepository.insertIfMissing(conversationId.get(), "CLI 会话", now);

        Scanner scanner = new Scanner(System.in);
        System.out.println("🤖 Java Agent MVP（CLI 模式）已启动！会话会写入 PostgreSQL agent_ui schema。");
        System.out.println("   命令: /clear 清空当前会话消息 | /new 开始新会话 | /help 帮助");
        System.out.println("   会话 ID: " + conversationId.get());
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
            if (handleMetaCommand(input, conversationId)) {
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
                conversationRepository.touchUpdatedAt(conversationId.get(), Instant.now(), null);
                conversationRepository.updateTitleIfDefault(
                        conversationId.get(),
                        trimTitle(input),
                        Instant.now(),
                        null);
            }
            catch (Exception e) {
                String message = e.getMessage();
                if (message != null && message.contains("url error")) {
                    System.err.println("❌ DashScope 配置错误：模型名或 base-url 不正确。");
                    System.err.println("   请检查 application.yml 中 spring.ai.dashscope.chat.options.model（推荐 qwen-plus）");
                }
                else if (message != null && message.contains("does not exist")) {
                    System.err.println("❌ SQL 执行失败: " + message);
                    System.err.println("   模型可能使用了不存在的列名，请重试或指定要查询的字段。");
                }
                else {
                    System.err.println("❌ 错误: " + message);
                }
            }
        }

        System.out.println("再见！");
    }

    private boolean handleMetaCommand(String input, AtomicReference<String> conversationId) {
        if ("/help".equalsIgnoreCase(input)) {
            System.out.println("""
                    命令:
                      /clear  清空当前会话的对话消息（保留会话记录）
                      /new    开始新会话（新 conversationId）
                      /help   显示此帮助
                      exit    退出
                    """);
            return true;
        }
        if ("/clear".equalsIgnoreCase(input)) {
            chatMemory.clear(conversationId.get());
            System.out.println("✓ 已清空当前会话消息。");
            return true;
        }
        if ("/new".equalsIgnoreCase(input)) {
            String previousId = conversationId.get();
            String newId = "cli-" + UUID.randomUUID();
            conversationId.set(newId);
            conversationRepository.insertIfMissing(newId, "CLI 会话", Instant.now());
            System.out.println("✓ 已开始新会话。");
            System.out.println("  旧会话: " + previousId);
            System.out.println("  新会话: " + newId);
            return true;
        }
        return false;
    }

    private static String trimTitle(String input) {
        String oneLine = input.replace('\n', ' ').replace('\r', ' ').strip();
        if (oneLine.length() > 80) {
            return oneLine.substring(0, 80) + "…";
        }
        return oneLine;
    }
}
