package com.flj.controller;

import com.flj.DTO.response.AnalyticsOverviewResponseDTO;
import com.flj.common.Result;
import com.flj.service.DataAnalyticsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端数据看板（聚合统计，无用户端对应界面）。
 * 前端路径 /data-analytics 不带 /admin 前缀，方法级 @PreAuthorize 限管理员——先例见 FileUploadController。
 */
@RestController
@RequestMapping("/api/data-analytics")
public class DataAnalyticsController {

    @Autowired
    private DataAnalyticsService dataAnalyticsService;

    /**
     * 看板总览：系统概况 + 情绪趋势（自评情绪分，ADR-0007）+ 咨询统计 + 用户活跃度，窗口均为近 7 天
     */
    @PreAuthorize("hasRole('2')")
    @GetMapping("/overview")
    public Result<AnalyticsOverviewResponseDTO> getOverview() {
        return Result.ok(dataAnalyticsService.getOverview());
    }
}
