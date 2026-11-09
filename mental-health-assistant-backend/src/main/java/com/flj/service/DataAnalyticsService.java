package com.flj.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.flj.DTO.response.AnalyticsOverviewResponseDTO;
import com.flj.entity.ConsultationMessage;
import com.flj.entity.ConsultationSession;
import com.flj.entity.EmotionDiary;
import com.flj.entity.User;
import com.flj.mapper.ConsultationMessageMapper;
import com.flj.mapper.ConsultationSessionMapper;
import com.flj.mapper.EmotionDiaryMapper;
import com.flj.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.DoubleSummaryStatistics;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 管理端数据看板聚合服务。
 * 口径决策：
 * - 平均情绪分聚合源 = 日记自评情绪分 1-10（ADR-0007），非 AI 情绪强度；
 * - 趋势窗口 = 近 7 个自然日（含当天），按日聚合、缺日补零（均值留 null）；
 * - 已软删除记录退出业务流程，不计入统计；
 * - 情绪趋势按 diary_date 归日（当日情绪），用户活跃度按行为发生时间归日（created_at/started_at）。
 */
@Service
public class DataAnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int TREND_DAYS = 7;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ConsultationSessionMapper sessionMapper;

    @Autowired
    private ConsultationMessageMapper messageMapper;

    @Autowired
    private EmotionDiaryMapper diaryMapper;

    /**
     * 看板总览：系统概况 + 情绪趋势 + 咨询统计 + 用户活跃度
     */
    public AnalyticsOverviewResponseDTO getOverview() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate windowStart = today.minusDays(TREND_DAYS - 1);
        LocalDateTime windowStartAt = windowStart.atStartOfDay();
        LocalDateTime tomorrowAt = today.plusDays(1).atStartOfDay();

        // 窗口内的行为明细（后续各视图共用；只取聚合需要的列）
        List<ConsultationSession> windowSessions = sessionMapper.selectList(new LambdaQueryWrapper<ConsultationSession>()
                .select(ConsultationSession::getUserId, ConsultationSession::getStartedAt)
                .eq(ConsultationSession::getDeleted, false)
                .ge(ConsultationSession::getStartedAt, windowStartAt)
                .lt(ConsultationSession::getStartedAt, tomorrowAt));
        List<EmotionDiary> windowDiaries = diaryMapper.selectList(new LambdaQueryWrapper<EmotionDiary>()
                .select(EmotionDiary::getUserId, EmotionDiary::getCreatedAt)
                .eq(EmotionDiary::getDeleted, false)
                .ge(EmotionDiary::getCreatedAt, windowStartAt)
                .lt(EmotionDiary::getCreatedAt, tomorrowAt));

        AnalyticsOverviewResponseDTO dto = new AnalyticsOverviewResponseDTO();
        dto.setSystemOverview(buildSystemOverview(today, tomorrowAt, windowSessions, windowDiaries));
        dto.setEmotionTrend(buildEmotionTrend(today, windowStart));
        dto.setConsultationStats(buildConsultationStats(today, windowStart, windowSessions));
        dto.setUserActivity(buildUserActivity(today, windowStart, tomorrowAt, windowSessions, windowDiaries));
        return dto;
    }

    /**
     * 系统概况：总量计数 + 活跃用户并集 + 平均自评情绪分
     */
    private AnalyticsOverviewResponseDTO.SystemOverview buildSystemOverview(
            LocalDate today, LocalDateTime tomorrowAt,
            List<ConsultationSession> windowSessions, List<EmotionDiary> windowDiaries) {
        LocalDateTime todayStart = today.atStartOfDay();

        Long totalUsers = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getUserType, 1));
        Long totalDiaries = diaryMapper.selectCount(new LambdaQueryWrapper<EmotionDiary>()
                .eq(EmotionDiary::getDeleted, false));
        Long totalSessions = sessionMapper.selectCount(new LambdaQueryWrapper<ConsultationSession>()
                .eq(ConsultationSession::getDeleted, false));
        Long todayNewDiaries = diaryMapper.selectCount(new LambdaQueryWrapper<EmotionDiary>()
                .eq(EmotionDiary::getDeleted, false)
                .ge(EmotionDiary::getCreatedAt, todayStart)
                .lt(EmotionDiary::getCreatedAt, tomorrowAt));
        Long todayNewSessions = sessionMapper.selectCount(new LambdaQueryWrapper<ConsultationSession>()
                .eq(ConsultationSession::getDeleted, false)
                .ge(ConsultationSession::getStartedAt, todayStart)
                .lt(ConsultationSession::getStartedAt, tomorrowAt));

        // 活跃用户 = 近 7 天发起过会话或创建过日记的去重并集
        Set<Long> activeUserIds = new HashSet<>();
        windowSessions.forEach(s -> activeUserIds.add(s.getUserId()));
        windowDiaries.forEach(d -> activeUserIds.add(d.getUserId()));
        activeUserIds.remove(null);

        AnalyticsOverviewResponseDTO.SystemOverview overview = new AnalyticsOverviewResponseDTO.SystemOverview();
        overview.setTotalUsers(totalUsers);
        overview.setActiveUsers((long) activeUserIds.size());
        overview.setTotalDiaries(totalDiaries);
        overview.setTodayNewDiaries(todayNewDiaries);
        overview.setTotalSessions(totalSessions);
        overview.setTodayNewSessions(todayNewSessions);
        overview.setAvgMoodScore(queryAvgMoodScore());
        return overview;
    }

    /**
     * 平均自评情绪分（ADR-0007）：全部未删除日记，保留 1 位小数；无日记时 0.0
     */
    private Double queryAvgMoodScore() {
        List<EmotionDiary> diaries = diaryMapper.selectList(new LambdaQueryWrapper<EmotionDiary>()
                .select(EmotionDiary::getMoodScore)
                .eq(EmotionDiary::getDeleted, false));
        DoubleSummaryStatistics stats = diaries.stream()
                .map(EmotionDiary::getMoodScore)
                .filter(Objects::nonNull)
                .mapToDouble(Integer::doubleValue)
                .summaryStatistics();
        return stats.getCount() == 0 ? 0.0
                : Math.round(stats.getAverage() * 10) / 10.0;
    }

    /**
     * 情绪趋势：按 diary_date 归日（当日情绪，补写历史落在窗口外）
     */
    private List<AnalyticsOverviewResponseDTO.EmotionTrendPoint> buildEmotionTrend(LocalDate today, LocalDate windowStart) {
        List<EmotionDiary> diaries = diaryMapper.selectList(new LambdaQueryWrapper<EmotionDiary>()
                .select(EmotionDiary::getDiaryDate, EmotionDiary::getMoodScore)
                .eq(EmotionDiary::getDeleted, false)
                .ge(EmotionDiary::getDiaryDate, windowStart)
                .le(EmotionDiary::getDiaryDate, today));
        Map<LocalDate, DoubleSummaryStatistics> statsByDate = diaries.stream()
                .filter(d -> d.getDiaryDate() != null && d.getMoodScore() != null)
                .collect(Collectors.groupingBy(EmotionDiary::getDiaryDate,
                        Collectors.summarizingDouble(d -> d.getMoodScore())));

        List<AnalyticsOverviewResponseDTO.EmotionTrendPoint> points = new ArrayList<>();
        for (LocalDate date = windowStart; !date.isAfter(today); date = date.plusDays(1)) {
            AnalyticsOverviewResponseDTO.EmotionTrendPoint point = new AnalyticsOverviewResponseDTO.EmotionTrendPoint();
            point.setDate(date.format(DATE_FORMAT));
            DoubleSummaryStatistics stats = statsByDate.get(date);
            if (stats == null) {
                // 无记录：条数 0、均值留 null（折线断开，不伪装成 0 分）
                point.setAvgMoodScore(null);
                point.setRecordCount(0L);
            } else {
                point.setAvgMoodScore(Math.round(stats.getAverage() * 10) / 10.0);
                point.setRecordCount(stats.getCount());
            }
            points.add(point);
        }
        return points;
    }

    /**
     * 咨询统计：总会话数 + 平均时长（全量）+ 近 7 天按日活动
     */
    private AnalyticsOverviewResponseDTO.ConsultationStats buildConsultationStats(
            LocalDate today, LocalDate windowStart, List<ConsultationSession> windowSessions) {
        AnalyticsOverviewResponseDTO.ConsultationStats stats = new AnalyticsOverviewResponseDTO.ConsultationStats();

        List<ConsultationSession> allSessions = sessionMapper.selectList(new LambdaQueryWrapper<ConsultationSession>()
                .select(ConsultationSession::getId, ConsultationSession::getStartedAt)
                .eq(ConsultationSession::getDeleted, false));
        stats.setTotalSessions((long) allSessions.size());
        stats.setAvgDurationMinutes(queryAvgDurationMinutes(allSessions));

        Map<LocalDate, List<ConsultationSession>> sessionsByDay = windowSessions.stream()
                .filter(s -> s.getStartedAt() != null)
                .collect(Collectors.groupingBy(s -> s.getStartedAt().toLocalDate()));

        List<AnalyticsOverviewResponseDTO.DailyTrendPoint> trend = new ArrayList<>();
        for (LocalDate date = windowStart; !date.isAfter(today); date = date.plusDays(1)) {
            List<ConsultationSession> sessions = sessionsByDay.getOrDefault(date, List.of());
            AnalyticsOverviewResponseDTO.DailyTrendPoint point = new AnalyticsOverviewResponseDTO.DailyTrendPoint();
            point.setDate(date.format(DATE_FORMAT));
            point.setSessionCount((long) sessions.size());
            point.setUserCount(sessions.stream()
                    .map(ConsultationSession::getUserId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .count());
            trend.add(point);
        }
        stats.setDailyTrend(trend);
        return stats;
    }

    /**
     * 平均会话时长（分钟，取整）：会话时长是派生量 = started_at → 最后一条消息时间（CONTEXT.md）。
     * 无消息的会话无法推导时长，不计入均值。
     */
    private Long queryAvgDurationMinutes(List<ConsultationSession> allSessions) {
        if (allSessions.isEmpty()) {
            return 0L;
        }
        Map<Long, LocalDateTime> lastMsgAtBySession = messageMapper.selectList(new LambdaQueryWrapper<ConsultationMessage>()
                        .select(ConsultationMessage::getSessionId, ConsultationMessage::getCreatedAt))
                .stream()
                .filter(m -> m.getSessionId() != null && m.getCreatedAt() != null)
                .collect(Collectors.toMap(ConsultationMessage::getSessionId, ConsultationMessage::getCreatedAt,
                        (a, b) -> a.isAfter(b) ? a : b));

        long totalMinutes = 0L;
        int counted = 0;
        for (ConsultationSession session : allSessions) {
            LocalDateTime lastMsgAt = lastMsgAtBySession.get(session.getId());
            if (session.getStartedAt() != null && lastMsgAt != null && lastMsgAt.isAfter(session.getStartedAt())) {
                totalMinutes += Duration.between(session.getStartedAt(), lastMsgAt).toMinutes();
                counted++;
            }
        }
        return counted == 0 ? 0L : Math.round((double) totalMinutes / counted);
    }

    /**
     * 用户活跃度：当日活跃（会话/日记用户并集）+ 当日新增注册 + 当日日记/会话用户
     */
    private List<AnalyticsOverviewResponseDTO.UserActivityPoint> buildUserActivity(
            LocalDate today, LocalDate windowStart, LocalDateTime tomorrowAt,
            List<ConsultationSession> windowSessions, List<EmotionDiary> windowDiaries) {
        Map<LocalDate, Set<Long>> sessionUsersByDay = windowSessions.stream()
                .filter(s -> s.getStartedAt() != null && s.getUserId() != null)
                .collect(Collectors.groupingBy(s -> s.getStartedAt().toLocalDate(),
                        Collectors.mapping(ConsultationSession::getUserId, Collectors.toSet())));
        Map<LocalDate, Set<Long>> diaryUsersByDay = windowDiaries.stream()
                .filter(d -> d.getCreatedAt() != null && d.getUserId() != null)
                .collect(Collectors.groupingBy(d -> d.getCreatedAt().toLocalDate(),
                        Collectors.mapping(EmotionDiary::getUserId, Collectors.toSet())));

        LocalDateTime windowStartAt = windowStart.atStartOfDay();
        Map<LocalDate, Long> newUsersByDay = userMapper.selectList(new LambdaQueryWrapper<User>()
                        .select(User::getCreatedAt)
                        .eq(User::getUserType, 1)
                        .ge(User::getCreatedAt, windowStartAt)
                        .lt(User::getCreatedAt, tomorrowAt))
                .stream()
                .filter(u -> u.getCreatedAt() != null)
                .collect(Collectors.groupingBy(u -> u.getCreatedAt().toLocalDate(), Collectors.counting()));

        List<AnalyticsOverviewResponseDTO.UserActivityPoint> points = new ArrayList<>();
        for (LocalDate date = windowStart; !date.isAfter(today); date = date.plusDays(1)) {
            AnalyticsOverviewResponseDTO.UserActivityPoint point = new AnalyticsOverviewResponseDTO.UserActivityPoint();
            point.setDate(date.format(DATE_FORMAT));
            Set<Long> sessionUsers = sessionUsersByDay.getOrDefault(date, Set.of());
            Set<Long> diaryUsers = diaryUsersByDay.getOrDefault(date, Set.of());
            Set<Long> activeUsers = new HashSet<>(sessionUsers);
            activeUsers.addAll(diaryUsers);
            point.setActiveUsers((long) activeUsers.size());
            point.setNewUsers(newUsersByDay.getOrDefault(date, 0L));
            point.setDiaryUsers((long) diaryUsers.size());
            point.setConsultationUsers((long) sessionUsers.size());
            points.add(point);
        }
        return points;
    }
}
