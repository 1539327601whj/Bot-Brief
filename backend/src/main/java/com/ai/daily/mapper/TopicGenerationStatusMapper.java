package com.ai.daily.mapper;

import com.ai.daily.entity.TopicGenerationStatus;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface TopicGenerationStatusMapper extends BaseMapper<TopicGenerationStatus> {

    @Select("SELECT * FROM topic_generation_status WHERE section_date = #{sectionDate} AND window_key = #{windowKey} AND topic_key = #{topicKey} LIMIT 1")
    TopicGenerationStatus findOne(
            @Param("sectionDate") LocalDate sectionDate,
            @Param("windowKey") String windowKey,
            @Param("topicKey") String topicKey
    );
}
