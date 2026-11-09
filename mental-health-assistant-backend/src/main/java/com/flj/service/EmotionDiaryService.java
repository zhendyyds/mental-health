package com.flj.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flj.AiService.EmotionAnalysisService;
import com.flj.AiService.AnalysisCallException;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.DTO.command.EmotionDiaryCreateDTO;
import com.flj.DTO.response.AdminEmotionDiaryResponseDTO;
import com.flj.DTO.response.EmotionDiaryResponseDTO;
import com.flj.DTO.response.SessionEmotionResponseDTO;
import com.flj.entity.EmotionDiary;
import com.flj.entity.User;
import com.flj.exception.BusinessException;
import com.flj.mapper.EmotionDiaryMapper;
import com.flj.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 情绪日记服务。创建走"保存 → 同步一次性分析 → 落库"（ADR-0005）：
 * 分析失败不阻塞日记保存，字段置 null 且不重试（日记不可变，无失效重算语义）。
 */
@Service
public class EmotionDiaryService {
    private static final Logger log = LoggerFactory.getLogger(EmotionDiaryService.class);

    // 管理端情绪评分范围筛选（参数名沿用前端既有 typo：moodScreRange）
    private static final Pattern MOOD_RANGE_PATTERN = Pattern.compile("^(\\d+)-(\\d+)$");

    @Autowired
    private EmotionDiaryMapper emotionDiaryMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private EmotionAnalysisService emotionAnalysisService;

    /**
     * 创建日记：保存 → 同步分析 → 结果落库；分析失败降级（日记保留、分析字段 null）
     */
    public EmotionDiaryResponseDTO createDiary(Long userId, EmotionDiaryCreateDTO createDTO) {
        // 服务端时区判定，拒绝未来日期
        if (createDTO.getDiaryDate().isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {
            throw new BusinessException("日记日期不能晚于今天");
        }

        EmotionDiary diary = EmotionDiary.builder()
                .userId(userId)
                .diaryDate(createDTO.getDiaryDate())
                .moodScore(createDTO.getMoodScore())
                .dominantEmotion(createDTO.getDominantEmotion())
                .emotionTriggers(createDTO.getEmotionTriggers())
                .diaryContent(createDTO.getDiaryContent())
                .sleepQuality(createDTO.getSleepQuality())
                .stressLevel(createDTO.getStressLevel())
                .deleted(false)
                .build();
        emotionDiaryMapper.insert(diary);

        // 同步一次性分析（ADR-0005）：失败不阻塞保存、不重试
        try {
            emotionAnalysisService.analyzeDiary(diary);
        } catch (Exception e) {
            log.warn("日记分析未完成，保留日记, diaryId={}, errorType={}", diary.getId(), e.getClass().getSimpleName());
        }

        // 回读：补齐数据库生成的时间戳与分析落库结果
        return toResponseDTO(emotionDiaryMapper.selectById(diary.getId()));
    }

    /**
     * 用户端日记分页：仅本人、未删除，按日期倒序、同日按 id 倒序
     */
    public Page<EmotionDiaryResponseDTO> pageUserDiaries(Long userId, int current, int size) {
        LambdaQueryWrapper<EmotionDiary> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(EmotionDiary::getUserId, userId)
                .eq(EmotionDiary::getDeleted, false)
                .orderByDesc(EmotionDiary::getDiaryDate)
                .orderByDesc(EmotionDiary::getId);
        Page<EmotionDiary> page = emotionDiaryMapper.selectPage(new Page<>(current, size), queryWrapper);
        Page<EmotionDiaryResponseDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream().map(this::toResponseDTO).toList());
        return result;
    }

    /**
     * 查询属于指定用户的未删除日记
     * @return 不存在/不属于该用户/已删除时返回 null（调用方 404 防枚举）
     */
    public EmotionDiary findOwnedDiary(Long userId, Long diaryId) {
        EmotionDiary diary = emotionDiaryMapper.selectById(diaryId);
        if (diary == null || !diary.getUserId().equals(userId) || Boolean.TRUE.equals(diary.getDeleted())) {
            return null;
        }
        return diary;
    }

    /**
     * 用户端日记详情（不存在/不属于该用户/已删除时返回 null）
     */
    public EmotionDiaryResponseDTO getDiaryDetail(Long userId, Long diaryId) {
        EmotionDiary diary = findOwnedDiary(userId, diaryId);
        return diary != null ? toResponseDTO(diary) : null;
    }

    /**
     * 管理端日记分页：全部记录（含已删除），可按 userId 精确、moodScreRange 范围筛选
     */
    public Page<AdminEmotionDiaryResponseDTO> pageAdminDiaries(int current, int size, Long userId, String moodScreRange) {
        LambdaQueryWrapper<EmotionDiary> queryWrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            queryWrapper.eq(EmotionDiary::getUserId, userId);
        }
        int[] range = parseMoodRange(moodScreRange);
        if (range != null) {
            queryWrapper.between(EmotionDiary::getMoodScore, range[0], range[1]);
        }
        queryWrapper.orderByDesc(EmotionDiary::getDiaryDate)
                .orderByDesc(EmotionDiary::getId);
        Page<EmotionDiary> page = emotionDiaryMapper.selectPage(new Page<>(current, size), queryWrapper);

        // 批量补用户名/昵称（避免逐行查库）
        Map<Long, User> userMap = page.getRecords().isEmpty() ? Map.of()
                : userMapper.selectBatchIds(page.getRecords().stream().map(EmotionDiary::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));

        Page<AdminEmotionDiaryResponseDTO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(page.getRecords().stream()
                .map(diary -> toAdminResponseDTO(diary, userMap.get(diary.getUserId())))
                .toList());
        return result;
    }

    /**
     * 重试失败的分析（失败恢复，非失效重算）。
     * 不变量：已有结果永不重算——实体层前置校验 + SQL is null 条件双重保证，并发下不会覆盖已有结果。
     * @return 不存在/不属于该用户/已删除时返回 null（调用方 404 防枚举）
     */
    public EmotionDiaryResponseDTO retryDiaryAnalysis(Long userId, Long diaryId) {
        EmotionDiary diary = findOwnedDiary(userId, diaryId);
        if (diary == null) {
            return null;
        }
        if (StrUtil.isNotBlank(diary.getAiEmotionAnalysis())) {
            throw new BusinessException("该日记已有分析结果，无需重试");
        }
        try {
            emotionAnalysisService.analyzeDiary(diary);
        } catch (AiCallRejectedException | AnalysisCallException e) {
            throw e;
        } catch (Exception e) {
            log.warn("日记分析重试失败, diaryId={}, errorType={}", diaryId, e.getClass().getSimpleName());
            throw new BusinessException("分析失败，请稍后再试");
        }
        return toResponseDTO(emotionDiaryMapper.selectById(diaryId));
    }

    /**
     * 管理端软删除（幂等：重复删除/不存在均视为成功）
     */
    public void softDeleteDiary(Long diaryId) {
        LambdaUpdateWrapper<EmotionDiary> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(EmotionDiary::getId, diaryId)
                .eq(EmotionDiary::getDeleted, false)
                .set(EmotionDiary::getDeleted, true);
        emotionDiaryMapper.update(null, updateWrapper);
    }

    /**
     * 解析情绪评分范围（"1-3"/"4-6"/"7-10"）；非法值忽略（不 500）
     */
    private int[] parseMoodRange(String moodScreRange) {
        if (StrUtil.isBlank(moodScreRange)) {
            return null;
        }
        Matcher matcher = MOOD_RANGE_PATTERN.matcher(moodScreRange.trim());
        if (!matcher.matches()) {
            return null;
        }
        int min = Integer.parseInt(matcher.group(1));
        int max = Integer.parseInt(matcher.group(2));
        if (min < 1 || max > 10 || min > max) {
            return null;
        }
        return new int[]{min, max};
    }

    private EmotionDiaryResponseDTO toResponseDTO(EmotionDiary diary) {
        EmotionDiaryResponseDTO dto = new EmotionDiaryResponseDTO();
        dto.setId(diary.getId());
        dto.setUserId(diary.getUserId());
        dto.setDiaryDate(diary.getDiaryDate());
        dto.setMoodScore(diary.getMoodScore());
        dto.setDominantEmotion(diary.getDominantEmotion());
        dto.setEmotionTriggers(diary.getEmotionTriggers());
        dto.setDiaryContent(diary.getDiaryContent());
        dto.setSleepQuality(diary.getSleepQuality());
        dto.setStressLevel(diary.getStressLevel());
        dto.setAiEmotionAnalysis(parseAnalysis(diary.getAiEmotionAnalysis()));
        dto.setCreatedAt(diary.getCreatedAt());
        dto.setUpdatedAt(diary.getUpdatedAt());
        return dto;
    }

    private AdminEmotionDiaryResponseDTO toAdminResponseDTO(EmotionDiary diary, User user) {
        AdminEmotionDiaryResponseDTO dto = new AdminEmotionDiaryResponseDTO();
        dto.setId(diary.getId());
        dto.setUserId(diary.getUserId());
        dto.setUsername(user != null ? user.getUsername() : null);
        dto.setNickname(user != null ? user.getNickname() : null);
        dto.setDiaryDate(diary.getDiaryDate());
        dto.setMoodScore(diary.getMoodScore());
        dto.setDominantEmotion(diary.getDominantEmotion());
        dto.setEmotionTriggers(diary.getEmotionTriggers());
        dto.setDiaryContent(diary.getDiaryContent());
        dto.setSleepQuality(diary.getSleepQuality());
        dto.setStressLevel(diary.getStressLevel());
        // 管理端保持 JSON 字符串（emotional.vue JSON.parse 契约）
        dto.setAiEmotionAnalysis(diary.getAiEmotionAnalysis());
        dto.setAiAnalysisUpdatedAt(diary.getAiAnalysisUpdatedAt());
        dto.setDeleted(Boolean.TRUE.equals(diary.getDeleted()));
        dto.setCreatedAt(diary.getCreatedAt());
        dto.setUpdatedAt(diary.getUpdatedAt());
        return dto;
    }

    private SessionEmotionResponseDTO parseAnalysis(String json) {
        if (StrUtil.isBlank(json)) {
            return null;
        }
        try {
            return JSONUtil.toBean(json, SessionEmotionResponseDTO.class);
        } catch (Exception e) {
            return null;
        }
    }
}
