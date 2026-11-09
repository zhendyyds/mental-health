package com.flj.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 情绪日记：用户对某天情绪与生活状态的主动记录（不关联咨询会话）。
 * 提交后不可变；同一日期可多条；管理端软删除。
 */
@Data
@TableName("emotion_diary")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmotionDiary {
    // 日记ID
    @TableId(type = IdType.AUTO)
    private Long id;

    // 用户ID（取自 JWT，不收前端值）
    @TableField("user_id")
    private Long userId;

    // 日记日期（可补写过去，禁未来）
    @TableField("diary_date")
    private LocalDate diaryDate;

    // 自评情绪分 1-10（主观度量，与 AI 情绪强度 0-100 不换算）
    @TableField("mood_score")
    private Integer moodScore;

    // 主要情绪（必填；不做枚举校验，长度由后端约束）
    @TableField("dominant_emotion")
    private String dominantEmotion;

    // 情绪触发因素
    @TableField("emotion_triggers")
    private String emotionTriggers;

    // 日记内容
    @TableField("diary_content")
    private String diaryContent;

    // 睡眠质量 1-5
    @TableField("sleep_quality")
    private Integer sleepQuality;

    // 压力水平 1-5
    @TableField("stress_level")
    private Integer stressLevel;

    // 日记情绪分析结果（JSON，提交时同步分析一次性落库，失败为 null 不重试）
    @TableField("ai_emotion_analysis")
    private String aiEmotionAnalysis;

    // 分析完成时间（analyzedAt 的落库来源）
    @TableField("ai_analysis_updated_at")
    private LocalDateTime aiAnalysisUpdatedAt;

    // 软删除标记 false:正常 true:已删除（用户端不可见，管理端可见）
    @TableField("deleted")
    private Boolean deleted;

    // 创建时间
    @TableField("created_at")
    private LocalDateTime createdAt;

    // 更新时间
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
