package com.ai.daily.mapper;

import com.ai.daily.entity.ScreenerScanHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ScreenerScanHistoryMapper extends BaseMapper<ScreenerScanHistory> {

    /**
     * 保留最新 {@code keep} 行时，最老那一行的 id。
     *
     * <p>不足 {@code keep} 行时子查询返回的就是现有最小 id，于是下面的 DELETE 一行都不删；
     * 一行都没有时 {@code COALESCE} 兜成 0，{@code id < 0} 同样删不掉任何东西。
     * **两种边界都靠 SQL 自己成立**，不在调用方补判断——补判断的地方就是将来漏判的地方。
     *
     * <p>子查询**必须**是独立的一条语句（id 由 Java 传进 DELETE）：MySQL 不允许
     * {@code DELETE FROM t ... (SELECT ... FROM t)} 这种在同一张表上又删又查的写法。
     */
    @Select("""
            SELECT COALESCE(MIN(id), 0) FROM (
                SELECT id FROM screener_scan_history ORDER BY id DESC LIMIT #{keep}
            ) t
            """)
    long oldestKeptId(@Param("keep") int keep);

    /** 裁剪：只删比「保留窗」更老的行。 */
    @Delete("DELETE FROM screener_scan_history WHERE id < #{oldestKeptId}")
    int deleteOlderThan(@Param("oldestKeptId") long oldestKeptId);
}