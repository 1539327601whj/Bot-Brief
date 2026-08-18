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

    @Select("SELECT id FROM reports WHERE edition = #{edition} AND report_date = #{reportDate} LIMIT 1")
    Long findIdByEditionAndReportDate(
            @Param("edition") String edition,
            @Param("reportDate") LocalDate reportDate
    );
}