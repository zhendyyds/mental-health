package com.flj.DTO.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 咨询会话列表项响应 DTO（用户端与管理端共用）
 */
@Data
public class ConsultationSessionResponseDTO {
    // 会话ID
    private Long id;

    // 用户ID
    private Long userId;

    // 用户昵称（仅管理端填充）
    private String userNickname;

    // 会话标题
    private String sessionTitle;

    // 开始时间
    private LocalDateTime startedAt;

    // 最后一条消息内容
    private String lastMessageContent;

    // 最后一条消息时间
    private LocalDateTime lastMessageTime;

    // 消息数
    private Integer messageCount;

    // 会话时长（分钟，由最后消息时间与开始时间推导）
    private Long durationMinutes;

    // 是否已删除（仅管理端有意义，用户端列表恒为 false）
    private Boolean deleted;
}
