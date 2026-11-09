package com.flj.DTO.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 活跃回复状态（ADR 0009）：前端刷新后恢复输入锁定态、会话列表"生成中"徽标的数据源。
 */
@Data
@AllArgsConstructor
public class ActiveReplyStatusDTO {
    /** 活跃回复所属的会话 id（数据库 id） */
    private Long sessionId;
    /** 活跃回复开始时间戳（毫秒） */
    private Long startedAt;
    private String requestId;
    private String clientRequestId;

    public ActiveReplyStatusDTO(Long sessionId, Long startedAt) {
        this(sessionId, startedAt, null, null);
    }
}
