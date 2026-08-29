package com.ai.daily.mapper;

import com.ai.daily.entity.TopicSection;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface TopicSectionMapper extends BaseMapper<TopicSection> {

    @Select("SELECT id FROM topic_sections WHERE section_date = #{sectionDate} AND edition = #{edition} AND topic_key = #{topicKey} LIMIT 1")
    Long findId(
            @Param("sectionDate") LocalDate sectionDate,
            @Param("edition") String edition,
            @Param("topicKey") String topicKey
    );
}
