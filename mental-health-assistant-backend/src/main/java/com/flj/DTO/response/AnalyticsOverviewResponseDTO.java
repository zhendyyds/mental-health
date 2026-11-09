package com.flj.DTO.response;

import lombok.Data;

import java.util.List;

/**
 * 管理端数据看板总览（dashboard.vue 消费）。
 * 趋势窗口均为近 7 天（含当天）；已软删除记录不计入统计（退出业务流程，见 CONTEXT.md「软删除」）。
 */
@Data
public class AnalyticsOverviewResponseDTO {

    // 系统概况卡片
    private SystemOverview systemOverview;

    // 情绪趋势分析（按 diary_date 聚合的自评情绪分，ADR-0007）
    private List<EmotionTrendPoint> emotionTrend;

    // 咨询会话统计
    private ConsultationStats consultationStats;

    // 用户活跃度趋势（按行为发生日聚合）
    private List<UserActivityPoint> userActivity;

    @Data
    public static class SystemOverview {
        // 注册普通用户总数（user_type=1）
        private Long totalUsers;
        // 近 7 天写过日记或发起过会话的去重用户（CONTEXT.md「活跃用户」）
        private Long activeUsers;
        // 未删除日记总数
        private Long totalDiaries;
        // 今日新建日记数（按 created_at，补写历史日期也计入"新增"）
        private Long todayNewDiaries;
        // 未删除会话总数
        private Long totalSessions;
        // 今日新发起会话数
        private Long todayNewSessions;
        // 全部未删除日记自评情绪分的均值（1-10，保留 1 位小数；无日记时为 0）
        private Double avgMoodScore;
    }

    @Data
    public static class EmotionTrendPoint {
        // 日记日期 yyyy-MM-dd
        private String date;
        // 当日自评情绪分均值；无记录的日子为 null（折线断开，不伪装成 0 分）
        private Double avgMoodScore;
        private Long recordCount;
    }

    @Data
    public static class ConsultationStats {
        // 未删除会话总数（与 systemOverview.totalSessions 同源）
        private Long totalSessions;
        // 全部会话的平均时长（分钟，started_at → 最后一条消息，派生量见 CONTEXT.md「会话时长」）
        private Long avgDurationMinutes;
        // 近 7 天咨询活动
        private List<DailyTrendPoint> dailyTrend;
    }

    @Data
    public static class DailyTrendPoint {
        // 会话发起日 yyyy-MM-dd
        private String date;
        // 当日发起的会话数
        private Long sessionCount;
        // 当日发起会话的去重用户数
        private Long userCount;
    }

    @Data
    public static class UserActivityPoint {
        // 日期 yyyy-MM-dd
        private String date;
        // 当日活跃用户（发起会话或创建日记的去重并集）
        private Long activeUsers;
        // 当日新注册普通用户数
        private Long newUsers;
        // 当日创建日记的去重用户数（按 created_at，非 diary_date）
        private Long diaryUsers;
        // 当日发起会话的去重用户数
        private Long consultationUsers;
    }
}
