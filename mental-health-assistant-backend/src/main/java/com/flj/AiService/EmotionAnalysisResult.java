package com.flj.AiService;

import java.util.List;

/**
 * LLM 情绪分析结构化输出结果
 * 字段命名保持干净（negative 而非 isNegative），避免 BeanOutputConverter 生成 JSON schema 时 LLM 输出歧义
 */
public record EmotionAnalysisResult(
        // 主要情绪（约 2 字短语）
        String primaryEmotion,
        // 情绪评分 0-100
        Integer emotionScore,
        // 是否负面情绪
        Boolean negative,
        // 风险等级 0:正常 1:关注 2:预警 3:危机
        Integer riskLevel,
        // 风险描述
        String riskDescription,
        // 温暖建议（负面疏导 / 正面鼓励）
        String suggestion,
        // 从 AI 回复中总结的 ≤10 字行动建议
        List<String> improvementSuggestions
) {}
