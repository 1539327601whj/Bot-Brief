package com.ai.daily.mapper;

import com.ai.daily.entity.ScreenerUniverseSnapshot;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

@Mapper
public interface ScreenerUniverseSnapshotMapper extends BaseMapper<ScreenerUniverseSnapshot> {

    /**
     * 读侧的判据：某个**交易日**的数据是否已经在库里。
     *
     * <p>头表只在**整批数据写完**那个事务里写，所以「有这一行」就等于「那次成功过」。
     * 失败不留行，因此不需要状态列。
     */
    @Select("SELECT COUNT(*) FROM screener_universe_snapshot WHERE trade_date = #{tradeDate}")
    int countByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /**
     * 写侧的幂等闸门：这个**自然日**是否已经跑过预取。
     *
     * <p>刻意不用 {@code trade_date} 当闸门。交易日与自然日不重合：周一休市时东财返回的
     * {@code latestTradeDate} 仍是上周五，按 {@code trade_date} 判「今天跑过没有」会永远判否，
     * 于是每一次重试都重新外呼一遍同样的数据。按自然日判则一天只跑一次，
     * 也正好对上「这份数据一天只需要取一次」这件事本身。
     */
    @Select("SELECT COUNT(*) FROM screener_universe_snapshot WHERE prefetch_date = #{prefetchDate}")
    int countByPrefetchDate(@Param("prefetchDate") LocalDate prefetchDate);

    /**
     * 取某交易日的头。**唯一键保证最多一行**，所以不用 {@code LIMIT}。
     *
     * <p>这里能用 {@code SELECT *} 而明细表那条必须逐列写出来：本表十列的名字与属性名
     * 全靠下划线转驼峰就能对上，明细表有个 {@code is_etf} 例外（理由写在那边的注释里）。
     */
    @Select("""
            SELECT * FROM screener_universe_snapshot
            WHERE trade_date = #{tradeDate}
            """)
    ScreenerUniverseSnapshot findByTradeDate(@Param("tradeDate") LocalDate tradeDate);

    /**
     * 写头。唯一键是 {@code trade_date}，所以重跑只覆盖同一行，不会堆出第二行。
     *
     * <p>用 {@code ON DUPLICATE KEY UPDATE} 而不是先删后插：删插之间有个窗口，
     * 读侧正好卡进去就会看到「有明细没头」，进而以为今天没预取过。
     */
    @Insert("""
            INSERT INTO screener_universe_snapshot
                (trade_date, prefetch_date, pool_size, listed_count, missing_count, cap_floor,
                 source, fetched_at, created_at, updated_at)
            VALUES
                (#{s.tradeDate}, #{s.prefetchDate}, #{s.poolSize}, #{s.listedCount}, #{s.missingCount},
                 #{s.capFloor}, #{s.source}, #{s.fetchedAt}, #{s.createdAt}, #{s.updatedAt})
            ON DUPLICATE KEY UPDATE
                prefetch_date = VALUES(prefetch_date),
                pool_size = VALUES(pool_size),
                listed_count = VALUES(listed_count),
                missing_count = VALUES(missing_count),
                cap_floor = VALUES(cap_floor),
                source = VALUES(source),
                fetched_at = VALUES(fetched_at),
                updated_at = VALUES(updated_at)
            """)
    int upsert(@Param("s") ScreenerUniverseSnapshot snapshot);
}