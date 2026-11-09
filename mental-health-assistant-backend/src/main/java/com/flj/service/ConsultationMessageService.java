package com.flj.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flj.DTO.response.ConsultationMessageResponseDTO;
import com.flj.entity.ConsultationMessage;
import com.flj.entity.ConsultationSession;
import com.flj.entity.AiRequestRecord;
import com.flj.mapper.ConsultationMessageMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ConsultationMessageService {
    @Autowired
    private ConsultationMessageMapper consultationMessageMapper;
    @Autowired
    private AiRequestService aiRequestService;

    public ConsultationMessage saveUserMessage(Long sessionId, String content, String emotion_tag) {
        // 构建用户消息实体
         ConsultationMessage userMessage = ConsultationMessage.builder()
            .sessionId(sessionId)
            .senderType(1)
            .messageType(1)
            .content(content)
            .emotionTag(emotion_tag)
            .createdAt(LocalDateTime.now())
            .build();

        consultationMessageMapper.insert(userMessage);
        return userMessage;
    }

    public ConsultationMessage saveAimessage(Long sessionId, String content, String aiModel) {
        ConsultationMessage message = ConsultationMessage.builder()
                .sessionId(sessionId)
                .senderType(2)
                .messageType(1)
                .content(content)
                .aiModel(aiModel)
                .createdAt(LocalDateTime.now())
                .build();
        // 插入数据库
        consultationMessageMapper.insert(message);
        return message;
    }

    public Integer getMessageCountBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId);

       Long count = consultationMessageMapper.selectCount(queryWrapper);
       return count.intValue();
    }

    // 获取会话的最后一条消息
    public ConsultationMessageResponseDTO getLastMessageBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId)
                // Match history/rebuild ordering; timestamps can be equal or backdated.
                .orderByDesc(ConsultationMessage::getId)
                .last("limit 1");

        ConsultationMessage lastMessage = consultationMessageMapper.selectOne(queryWrapper);
        return lastMessage != null ? convertToResponseDTO(lastMessage) : null;

    }

    // 按会话查询全部消息（id 升序，用于会话详情回放）
    public List<ConsultationMessageResponseDTO> listMessagesBySessionId(Long sessionId) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId)
                .orderByAsc(ConsultationMessage::getId);

        List<ConsultationMessageResponseDTO> messages = consultationMessageMapper.selectList(queryWrapper).stream()
                .map(this::convertToResponseDTO)
                .toList();
        if (aiRequestService != null) {
            Map<String, AiRequestRecord> requests = aiRequestService.findByRequestIds(messages.stream()
                    .map(ConsultationMessageResponseDTO::getRequestId).filter(Objects::nonNull).distinct().toList());
            for (ConsultationMessageResponseDTO message : messages) {
                AiRequestRecord request = requests.get(message.getRequestId());
                if (request != null) {
                    message.setRequestStatus(request.getStatus()); message.setRequestErrorCode(request.getErrorCode());
                    message.setRequestErrorMessage(request.getErrorMessage()); message.setSaved(request.isSaved());
                }
            }
        }
        return messages;
    }

    // 取会话最近 N 条消息（id 倒序，用于重建对话记忆）
    public List<ConsultationMessage> getRecentMessages(Long sessionId, int limit) {
        LambdaQueryWrapper<ConsultationMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationMessage::getSessionId, sessionId)
                .orderByDesc(ConsultationMessage::getId)
                .last("limit " + limit);

        return consultationMessageMapper.selectList(queryWrapper);
    }

    private ConsultationMessageResponseDTO convertToResponseDTO(ConsultationMessage message) {
        if (message == null) {
            return null;
        }

        // 手动逐字段赋值，确保转换的准确性和可控性
        ConsultationMessageResponseDTO responseDTO = new ConsultationMessageResponseDTO();
        responseDTO.setId(message.getId());
        responseDTO.setSessionId(message.getSessionId());
        responseDTO.setRequestId(message.getRequestId());
        responseDTO.setSenderType(message.getSenderType());
        responseDTO.setMessageType(message.getMessageType());
        responseDTO.setContent(message.getContent());
        responseDTO.setEmotionTag(message.getEmotionTag());
        responseDTO.setAiModel(message.getAiModel());
        responseDTO.setCreatedAt(message.getCreatedAt());

        // 设置描述字段（通过实体方法获取）
        responseDTO.setSenderTypeDesc(message.getSenderTypeDesc());
        responseDTO.setMessageTypeDesc(message.getMessageTypeDesc());

        // 计算消息长度
        responseDTO.calculateContentLength();

        return responseDTO;
    }
}
