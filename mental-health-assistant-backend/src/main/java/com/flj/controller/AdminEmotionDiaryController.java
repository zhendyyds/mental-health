package com.flj.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flj.DTO.response.AdminEmotionDiaryResponseDTO;
import com.flj.common.Result;
import com.flj.service.EmotionDiaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端情绪日记（权限规则见 SecurityConfig：/api/admin/** 需管理员角色）
 */
@RestController
@RequestMapping("/api/admin/emotion-diary")
public class AdminEmotionDiaryController {

    @Autowired
    private EmotionDiaryService emotionDiaryService;

    /**
     * 管理端日记分页（含已删除；userId 精确、moodScreRange 情绪评分范围筛选）
     */
    @GetMapping("/page")
    public Result<Page<AdminEmotionDiaryResponseDTO>> getDiaryPage(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) Long userId,
            // 参数名沿用前端既有 typo：moodScreRange
            @RequestParam(required = false) String moodScreRange) {
        return Result.ok(emotionDiaryService.pageAdminDiaries(current, size, userId, moodScreRange));
    }

    /**
     * 管理端软删除日记（幂等）
     */
    @DeleteMapping("/{diaryId}")
    public Result<Void> deleteDiary(@PathVariable Long diaryId) {
        emotionDiaryService.softDeleteDiary(diaryId);
        return Result.ok();
    }
}
