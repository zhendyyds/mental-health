package com.flj.config;

import com.flj.AiService.PromptManage;
import com.flj.rag.qa.KnowledgeQaPrompt;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Schedulers;

@Configuration
public class ChatClientConfig {
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(30) // 保留最新30条消息
                .build();
    }

    @Bean("open-ai")
    public ChatClient openAiChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel)
                // Keep memory preparation inside the reply's start/stop gate, so a
                // cancelled queued advisor cannot write into a later turn's memory.
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory())
                        .scheduler(Schedulers.immediate()).build())
                .defaultSystem(PromptManage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT).build();
    }

    /**
     * 情绪分析专用 ChatClient：不挂 ChatMemory advisor（分析上下文直接拼入 prompt，
     * 避免污染对话记忆、避免依赖记忆重建）
     */
    @Bean("emotion-analysis")
    public ChatClient emotionAnalysisChatClient(OpenAiChatModel openAiChatModel) {
        return ChatClient.builder(openAiChatModel).build();
    }

    @Bean("knowledge-qa")
    public ChatClient knowledgeQaChatClient(OpenAiChatModel openAiChatModel) {
        return KnowledgeQaPrompt.client(openAiChatModel);
    }
}
