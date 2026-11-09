package com.flj.DTO.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话情绪分析响应 DTO
 * 分析结果与缓存状态；降级结果不能被当作本轮新分析成功。
 */
@Data
public class SessionEmotionResponseDTO {
    // 主要情绪
    private String primaryEmotion;
    // 情绪评分 0-100
    private Integer emotionScore;
    // 是否负面情绪
    private Boolean isNegative;
    // 风险等级 0:正常 1:关注 2:预警 3:危机
    private Integer riskLevel;
    // 风险描述
    private String riskDescription;
    // 温暖建议
    private String suggestion;
    // 治愈小行动建议列表
    private List<String> improvementSuggestions;

    // 分析完成时间；null 表示从未分析（中性占位数据）
    private LocalDateTime analyzedAt;

    // 本次实际输入覆盖的最新咨询消息ID；旧快照缺失此值时不能视为新鲜。
    private Long coveredMessageId;
    private String analysisStatus;
    private boolean cached;
    private boolean stale;

    /**
     * 中性默认值（占位）
     */
    public static SessionEmotionResponseDTO neutral() {
        SessionEmotionResponseDTO dto = new SessionEmotionResponseDTO();
        dto.setPrimaryEmotion("中性");
        dto.setEmotionScore(50);
        dto.setIsNegative(false);
        dto.setRiskLevel(0);
        dto.setRiskDescription("");
        dto.setSuggestion("情绪状态平稳");
        dto.setImprovementSuggestions(new ArrayList<>());
        dto.setAnalysisStatus("EMPTY");
        return dto;
    }
}
