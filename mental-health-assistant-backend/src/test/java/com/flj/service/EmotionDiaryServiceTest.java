package com.flj.service;

import cn.hutool.json.JSONUtil;
import com.flj.AiService.AnalysisCallException;
import com.flj.AiService.EmotionAnalysisService;
import com.flj.AiService.governance.AiCallRejectedException;
import com.flj.DTO.command.EmotionDiaryCreateDTO;
import com.flj.DTO.response.EmotionDiaryResponseDTO;
import com.flj.DTO.response.SessionEmotionResponseDTO;
import com.flj.entity.EmotionDiary;
import com.flj.exception.BusinessException;
import com.flj.mapper.EmotionDiaryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmotionDiaryServiceTest {
    private EmotionDiaryService service;
    private EmotionDiaryMapper mapper;
    private EmotionAnalysisService analysis;
    private AtomicReference<EmotionDiary> stored;

    @BeforeEach
    void setUp() {
        service = new EmotionDiaryService();
        mapper = mock(EmotionDiaryMapper.class);
        analysis = mock(EmotionAnalysisService.class);
        stored = new AtomicReference<>();
        ReflectionTestUtils.setField(service, "emotionDiaryMapper", mapper);
        ReflectionTestUtils.setField(service, "emotionAnalysisService", analysis);
        when(mapper.insert(any(EmotionDiary.class))).thenAnswer(call -> {
            EmotionDiary row = call.getArgument(0);
            row.setId(4L);
            stored.set(row);
            return 1;
        });
        when(mapper.selectById(4L)).thenAnswer(call -> stored.get());
    }

    @Test
    void createPersistsDiaryBeforeAnalysisAndKeepsItWhenAnalysisTimesOut() {
        when(analysis.analyzeDiary(any(EmotionDiary.class)))
                .thenThrow(new AnalysisCallException("504", "分析超时", "request-safe", "TIMED_OUT"));
        EmotionDiaryResponseDTO response = service.createDiary(7L, input());
        assertEquals(4L, response.getId());
        assertEquals("今日内容", response.getDiaryContent());
        assertNull(response.getAiEmotionAnalysis());
        InOrder order = inOrder(mapper, analysis);
        order.verify(mapper).insert(any(EmotionDiary.class));
        order.verify(analysis).analyzeDiary(any(EmotionDiary.class));
        order.verify(mapper).selectById(4L);
        verify(mapper, never()).updateById(any(EmotionDiary.class));
    }

    @Test
    void failedCreateAnalysisNeverClearsAnotherConcurrentSuccessfulResult() {
        when(analysis.analyzeDiary(any(EmotionDiary.class))).thenAnswer(call -> {
            publishWinner("并发成功");
            throw new IllegalStateException("SECRET upstream payload");
        });
        EmotionDiaryResponseDTO response = service.createDiary(7L, input());
        assertEquals("并发成功", response.getAiEmotionAnalysis().getPrimaryEmotion());
        verify(mapper, never()).updateById(any(EmotionDiary.class));
    }

    @Test
    void retryDoesNotAnalyzeAnExistingResult() {
        storeDiary();
        publishWinner("已有成功");
        assertThrows(BusinessException.class, () -> service.retryDiaryAnalysis(7L, 4L));
        verifyNoInteractions(analysis);
    }

    @ParameterizedTest
    @ValueSource(strings = {"429", "504"})
    void retryPreservesPublicFailureTypeAndStatus(String code) {
        storeDiary();
        RuntimeException failure = code.equals("429")
                ? new AiCallRejectedException("429", "请求过于频繁", 12)
                : new AnalysisCallException("504", "分析超时", "request-safe", "TIMED_OUT");
        when(analysis.analyzeDiary(any(EmotionDiary.class))).thenThrow(failure);
        assertSame(failure, assertThrows(RuntimeException.class, () -> service.retryDiaryAnalysis(7L, 4L)));
        assertNull(stored.get().getAiEmotionAnalysis());
    }

    @Test
    void retrySuccessfulPersistenceIsOwnedByTheSharedAnalysisTask() {
        storeDiary();
        when(analysis.analyzeDiary(any(EmotionDiary.class))).thenAnswer(call -> {
            publishWinner("完成");
            return SessionEmotionResponseDTO.neutral();
        });
        assertEquals("完成", service.retryDiaryAnalysis(7L, 4L).getAiEmotionAnalysis().getPrimaryEmotion());
        verify(analysis, times(1)).analyzeDiary(any(EmotionDiary.class));
        verify(mapper, never()).updateById(any(EmotionDiary.class));
        verify(mapper, never()).update(isNull(), any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
    }

    @Test
    void retryRedactsUnexpectedFailureWithoutClearingDiary() {
        storeDiary();
        when(analysis.analyzeDiary(any(EmotionDiary.class)))
                .thenThrow(new IllegalStateException("SECRET database exception"));
        BusinessException failure = assertThrows(BusinessException.class, () -> service.retryDiaryAnalysis(7L, 4L));
        assertEquals("分析失败，请稍后再试", failure.getMessage());
        assertFalse(failure.getMessage().contains("SECRET"));
        assertNotNull(stored.get());
    }

    @Test
    void foreignAndDeletedDiariesNeverStartAnalysis() {
        storeDiary();
        assertNull(service.retryDiaryAnalysis(8L, 4L));
        stored.get().setDeleted(true);
        assertNull(service.retryDiaryAnalysis(7L, 4L));
        verifyNoInteractions(analysis);
    }

    private void storeDiary() {
        stored.set(EmotionDiary.builder().id(4L).userId(7L).deleted(false)
                .diaryDate(LocalDate.now(ZoneId.of("Asia/Shanghai"))).diaryContent("今日内容").build());
    }

    private void publishWinner(String emotion) {
        SessionEmotionResponseDTO result = SessionEmotionResponseDTO.neutral();
        result.setPrimaryEmotion(emotion);
        result.setAnalysisStatus("SUCCEEDED");
        result.setAnalyzedAt(LocalDateTime.of(2026, 11, 6, 12, 0));
        stored.get().setAiEmotionAnalysis(JSONUtil.toJsonStr(result));
        stored.get().setAiAnalysisUpdatedAt(result.getAnalyzedAt());
    }

    private static EmotionDiaryCreateDTO input() {
        EmotionDiaryCreateDTO dto = new EmotionDiaryCreateDTO();
        dto.setDiaryDate(LocalDate.now(ZoneId.of("Asia/Shanghai")));
        dto.setMoodScore(7);
        dto.setDominantEmotion("平静");
        dto.setDiaryContent("今日内容");
        return dto;
    }
}
