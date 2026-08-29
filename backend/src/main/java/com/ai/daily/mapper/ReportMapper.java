package com.ai.daily.mapper;

import com.ai.daily.entity.Report;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/**
 * Report Mapper
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {

    @Select("SELECT id FROM reports WHERE ingest_key = #{ingestKey} LIMIT 1")
    Long findIdByIngestKey(@Param("ingestKey") String ingestKey);

    @Select("SELECT id FROM reports WHERE user_id = 0 AND edition = #{edition} AND report_date = #{reportDate} LIMIT 1")
    Long findIdByEditionAndReportDate(
            @Param("edition") String edition,
            @Param("reportDate") LocalDate reportDate
    );

    @Select("SELECT id FROM reports WHERE user_id = #{userId} AND edition = #{edition} AND report_date = #{reportDate} LIMIT 1")
    Long findIdByUserEditionAndReportDate(
            @Param("userId") Long userId,
            @Param("edition") String edition,
            @Param("reportDate") LocalDate reportDate
    );

    @Select("SELECT id FROM reports WHERE user_id = #{userId} AND edition = #{edition} AND report_date = #{reportDate} AND display_time = #{displayTime} LIMIT 1")
    Long findIdByUserEditionDateAndTime(
            @Param("userId") Long userId,
            @Param("edition") String edition,
            @Param("reportDate") LocalDate reportDate,
            @Param("displayTime") java.time.LocalTime displayTime
    );
}