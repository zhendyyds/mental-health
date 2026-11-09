package com.flj.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.flj.entity.ConsultationMessage;
import com.flj.mapper.ConsultationMessageMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsultationMessageOrderingTest {
    @Test
    void latestMessageUsesTheSameIdOrderAsHistoryAndSnapshotCoverage() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "ordering-test"),
                ConsultationMessage.class);
        ConsultationMessageMapper mapper = mock(ConsultationMessageMapper.class);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
        ConsultationMessageService service = new ConsultationMessageService();
        ReflectionTestUtils.setField(service, "consultationMessageMapper", mapper);
        service.getLastMessageBySessionId(7L);
        ArgumentCaptor<Wrapper<ConsultationMessage>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(query.capture());
        assertTrue(query.getValue().getSqlSegment().contains("ORDER BY id DESC"));
    }
}
