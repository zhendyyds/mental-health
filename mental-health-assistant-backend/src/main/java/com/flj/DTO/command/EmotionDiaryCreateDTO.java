package com.flj.DTO.command;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

/**
 * 情绪日记创建命令（userId 一律取 JWT，不在此收）
 */
@Data
public class EmotionDiaryCreateDTO {
    @NotNull(message = "日记日期不能为空")
    private LocalDate diaryDate;

    @NotNull(message = "情绪评分不能为空")
    @Min(value = 1, message = "情绪评分最低1分")
    @Max(value = 10, message = "情绪评分最高10分")
    private Integer moodScore;

    // 不做枚举校验（前端选项与管理端映射存在漂移，仅约束长度）
    @NotBlank(message = "主要情绪不能为空")
    @Size(max = 20, message = "主要情绪不能超过20个字符")
    private String dominantEmotion;

    @Size(max = 1000, message = "情绪触发因素不能超过1000个字符")
    private String emotionTriggers;

    @Size(max = 2000, message = "日记内容不能超过2000个字符")
    private String diaryContent;

    @Min(value = 1, message = "睡眠质量为1-5")
    @Max(value = 5, message = "睡眠质量为1-5")
    private Integer sleepQuality;

    @Min(value = 1, message = "压力水平为1-5")
    @Max(value = 5, message = "压力水平为1-5")
    private Integer stressLevel;
}
