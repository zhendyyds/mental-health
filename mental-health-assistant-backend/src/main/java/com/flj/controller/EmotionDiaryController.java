package com.flj.controller;

import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import com.flj.DTO.command.EmotionDiaryCreateDTO;
import com.flj.DTO.response.EmotionDiaryResponseDTO;
import com.flj.common.Result;
import com.flj.common.ResultCode;
import com.flj.service.EmotionDiaryService;
import com.flj.util.JwtTokenUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端情绪日记（userId 一律取 JWT；归属不符返回 404 防枚举，对齐会话先例）
 */
@RestController
@RequestMapping("/api/emotion-diary")
public class EmotionDiaryController {

    @Autowired
    private EmotionDiaryService emotionDiaryService;

    /**
     * 创建日记（提交时同步一次性分析，ADR-0005；分析失败不阻塞保存）
     */
    @PostMapping
    public Result<EmotionDiaryResponseDTO> createDiary(@Valid @RequestBody EmotionDiaryCreateDTO createDTO) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(emotionDiaryService.createDiary(userId, createDTO));
    }

    /**
     * 本人日记分页（仅本人、未删除，按日期倒序）
     */
    @GetMapping("/page")
    public Result<Page<EmotionDiaryResponseDTO>> pageDiaries(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        return Result.ok(emotionDiaryService.pageUserDiaries(userId, current, size));
    }

    /**
     * 本人日记详情（归属不符/已删除返回 404 防枚举）
     */
    @GetMapping("/{diaryId}")
    public Result<EmotionDiaryResponseDTO> getDiary(@PathVariable Long diaryId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        EmotionDiaryResponseDTO diary = emotionDiaryService.getDiaryDetail(userId, diaryId);
        if (diary == null) {
            return Result.error("404", "日记不存在", null);
        }
        return Result.ok(diary);
    }

    /**
     * 重试失败的分析（仅当分析缺失时可触发；已有结果永不重算——ADR-0005 不变量）
     */
    @PostMapping("/{diaryId}/analysis/retry")
    public Result<EmotionDiaryResponseDTO> retryAnalysis(@PathVariable Long diaryId) {
        Long userId = getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getCode(), ResultCode.UNAUTHORIZED.getMsg(), null);
        }
        EmotionDiaryResponseDTO diary = emotionDiaryService.retryDiaryAnalysis(userId, diaryId);
        if (diary == null) {
            return Result.error("404", "日记不存在", null);
        }
        return Result.ok(diary);
    }

    // 从当前请求的 JWT 中解析用户ID
    private Long getCurrentUserId() {
        String token = JwtTokenUtil.getCurrentToken();
        if (token == null) {
            return null;
        }
        DecodedJWT jwt = JwtTokenUtil.verifyToken(token);
        return jwt.getClaim("userId").asLong();
    }
}
