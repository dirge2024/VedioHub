package com.example.server.utils;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekUtils {

    private static final String SYSTEM_PROMPT = """
            # Role
            你是一位拥有认知心理学背景的资深信息架构师，负责从语音转录文本中提取高价值信息并重构逻辑。

            # Goals
            忽略口语废话、重复和语气词，输出结构清晰、客观专业的分析报告。

            # Constraints
            1. 文本过短或无意义时，输出“无法提取有效信息”。
            2. 不输出开场白或结束语。
            3. 严格使用以下 Markdown 结构：

            ## 核心摘要
            ## 深度洞察
            ### 1. 观点标题
            ## 原始内容精选
            ## 领域标签
            """;

    private final ChatModel chatModel;

    public DeepSeekUtils(@Value("${ai.deepseek.api-key}") String apiKey,
                         @Value("${ai.deepseek.base-url}") String baseUrl,
                         @Value("${ai.deepseek.model:deepseek-ai/DeepSeek-R1-Distill-Qwen-32B}") String modelName) {
        this.chatModel = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .build();
    }

    public String analyzeContent(String content) {
        if (content == null || content.isBlank()) {
            return "无法提取有效信息";
        }
        return chatModel.chat(SYSTEM_PROMPT + "\n\n待分析文本：\n" + content);
    }
}
