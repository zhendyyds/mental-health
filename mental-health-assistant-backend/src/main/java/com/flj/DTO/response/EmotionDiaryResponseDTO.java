package com.flj.DTO.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 情绪日记响应 DTO（用户端）
 * aiEmotionAnalysis 为已解析对象（与情绪快照同构），null 表示分析缺失
 */
@Data
public class EmotionDiaryResponseDTO {
    private Long id;
    private Long userId;
    private LocalDate diaryDate;
    // 自评情绪分 1-10（主观度量，与 AI 情绪强度 0-100 不换算）
    private Integer moodScore;
    private String dominantEmotion;
    private String emotionTriggers;
    private String diaryContent;
    private Integer sleepQuality;
    private Integer stressLevel;
    // 日记情绪分析结果（解析后对象；null 表示分析缺失）
    private SessionEmotionResponseDTO aiEmotionAnalysis;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
