package com.flj.DTO.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 情绪日记响应 DTO（管理端）
 * aiEmotionAnalysis 保持 JSON 字符串（emotional.vue 既有 JSON.parse 契约）
 */
@Data
public class AdminEmotionDiaryResponseDTO {
    private Long id;
    private Long userId;
    private String username;
    private String nickname;
    private LocalDate diaryDate;
    private Integer moodScore;
    private String dominantEmotion;
    private String emotionTriggers;
    private String diaryContent;
    private Integer sleepQuality;
    private Integer stressLevel;
    // AI 情绪分析结果（JSON 字符串，null 表示分析缺失）
    private String aiEmotionAnalysis;
    private LocalDateTime aiAnalysisUpdatedAt;
    private Boolean deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
