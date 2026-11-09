package com.flj.service;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flj.DTO.command.ConsultationSessionCreateDTO;
import com.flj.DTO.response.ConsultationMessageResponseDTO;
import com.flj.DTO.response.ConsultationSessionResponseDTO;
import com.flj.entity.ConsultationSession;
import com.flj.entity.User;
import com.flj.mapper.ConsultationSessionMapper;
import com.flj.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class ConsultationSessionService {
    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConsultationSessionMapper consultationSessionMapper;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    public ConsultationSession createSession(Long userId, ConsultationSessionCreateDTO createDTO) {
        // 验证用户是否存在
        User user = userMapper.selectById(userId);
        if (user != null) {
            // 创建会话记录
             ConsultationSession session = ConsultationSession.builder()
                    .userId(userId)
                    .sessionTitle(createDTO.getSessionTitle())
                    .startedAt(LocalDateTime.now())
                    .deleted(false)
                    .build();
            // 如果未提供标题
            if (StrUtil.isBlank(createDTO.getSessionTitle())) {
                session.setSessionTitle(String.format("小暖AI助手 - " + DateUtil.format(LocalDateTime.now(), "MM-dd HH:mm")));
            }

            // 插入记录
            consultationSessionMapper.insert(session);
            return session;
        }

        return null;
    }

    /**
     * 查询属于指定用户的会话（不区分是否已删除）
     * @return 会话不存在或不属于该用户时返回 null
     */
    public ConsultationSession findOwnedSession(Long userId, Long sessionId) {
        ConsultationSession session = consultationSessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return null;
        }
        return session;
    }

    /**
     * 软删除会话（用户端不可见，管理端仍可查看）
     * @return 会话不存在或不属于该用户时返回 false，否则 true（重复删除幂等）
     */
    public boolean softDeleteSession(Long userId, Long sessionId) {
        ConsultationSession session = findOwnedSession(userId, sessionId);
        if (session == null) {
            return false;
        }
        if (Boolean.TRUE.equals(session.getDeleted())) {
            return true;
        }
        ConsultationSession update = new ConsultationSession();
        update.setId(sessionId);
        update.setDeleted(true);
        consultationSessionMapper.updateById(update);
        return true;
    }

    /**
     * 用户端会话分页：仅本人、未删除，按开始时间倒序
     */
    public Page<ConsultationSessionResponseDTO> pageUserSessions(Long userId, int pageNum, int pageSize) {
        LambdaQueryWrapper<ConsultationSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationSession::getUserId, userId)
                .eq(ConsultationSession::getDeleted, false)
                .orderByDesc(ConsultationSession::getStartedAt);
        Page<ConsultationSession> page = consultationSessionMapper.selectPage(new Page<>(pageNum, pageSize), queryWrapper);
        return convertPage(page, false);
    }

    /**
     * 管理端会话分页：全部会话（含已删除），按开始时间倒序，带用户昵称
     */
    public Page<ConsultationSessionResponseDTO> pageAdminSessions(int pageNum, int pageSize) {
        LambdaQueryWrapper<ConsultationSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.orderByDesc(ConsultationSession::getStartedAt);
        Page<ConsultationSession> page = consultationSessionMapper.selectPage(new Page<>(pageNum, pageSize), queryWrapper);
        return convertPage(page, true);
    }

    private Page<ConsultationSessionResponseDTO> convertPage(Page<ConsultationSession> page, boolean includeUser) {
        Page<ConsultationSessionResponseDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(session -> toResponseDTO(session, includeUser))
                .toList());
        return result;
    }

    private ConsultationSessionResponseDTO toResponseDTO(ConsultationSession session, boolean includeUser) {
        ConsultationSessionResponseDTO dto = new ConsultationSessionResponseDTO();
        dto.setId(session.getId());
        dto.setUserId(session.getUserId());
        dto.setSessionTitle(session.getSessionTitle());
        dto.setStartedAt(session.getStartedAt());
        dto.setDeleted(Boolean.TRUE.equals(session.getDeleted()));
        dto.setDurationMinutes(0L);

        // 聚合消息数据
        dto.setMessageCount(consultationMessageService.getMessageCountBySessionId(session.getId()));
        ConsultationMessageResponseDTO lastMessage = consultationMessageService.getLastMessageBySessionId(session.getId());
        if (lastMessage != null) {
            dto.setLastMessageContent(lastMessage.getContent());
            dto.setLastMessageTime(lastMessage.getCreatedAt());
            if (session.getStartedAt() != null && lastMessage.getCreatedAt() != null) {
                long minutes = Duration.between(session.getStartedAt(), lastMessage.getCreatedAt()).toMinutes();
                dto.setDurationMinutes(Math.max(minutes, 0L));
            }
        }

        // 管理端附带用户信息
        if (includeUser) {
            User user = userMapper.selectById(session.getUserId());
            dto.setUserNickname(user != null ? user.getDisplayName() : "未知用户");
        }
        return dto;
    }
}
