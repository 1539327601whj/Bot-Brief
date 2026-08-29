package com.ai.daily.service;

import com.ai.daily.entity.TopicSection;
import com.ai.daily.mapper.TopicSectionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TopicSectionServiceTest {

    @Test
    void findForKeepsRequestedTopicOrderAndIgnoresCase() {
        TopicSectionMapper mapper = mock(TopicSectionMapper.class);
        TopicSectionService service = new TopicSectionService();
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        when(mapper.selectList(any())).thenReturn(List.of(section("数据库"), section("安全")));

        List<TopicSection> found = service.findFor(
                LocalDate.of(2026, 8, 28), ReportWindows.W06_12, List.of("安全", "区块链", "数据库"));

        assertThat(found).extracting(TopicSection::getTopicKey).containsExactly("安全", "数据库");
    }

    private TopicSection section(String topic) {
        TopicSection section = new TopicSection();
        section.setTopicKey(topic);
        section.setContent("## " + topic + "\n\n正文");
        return section;
    }
}
