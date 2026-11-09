package com.flj.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flj.DTO.response.ConsultationMessageResponseDTO;
import com.flj.DTO.response.ConsultationSessionResponseDTO;
import com.flj.common.Result;
import com.flj.service.ConsultationMessageService;
import com.flj.service.ConsultationSessionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端咨询记录（权限规则见 SecurityConfig：/api/admin/** 需管理员角色）
 */
@RestController
@RequestMapping("/api/admin/consultation")
public class AdminConsultationController {

    @Autowired
    private ConsultationSessionService consultationSessionService;

    @Autowired
    private ConsultationMessageService consultationMessageService;

    /**
     * 管理端会话分页（全部会话含已删除，带用户昵称与删除标记）
     */
    @GetMapping("/sessions")
    public Result<Page<ConsultationSessionResponseDTO>> getConsultationPage(
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.ok(consultationSessionService.pageAdminSessions(currentPage, size));
    }

    /**
     * 管理端会话消息详情
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessageResponseDTO>> getSessionDetail(@PathVariable Long sessionId) {
        return Result.ok(consultationMessageService.listMessagesBySessionId(sessionId));
    }
}
