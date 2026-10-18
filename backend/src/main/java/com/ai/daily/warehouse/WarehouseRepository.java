package com.ai.daily.warehouse;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * {@code md_*} 表的读写。
 *
 * <p><b>为什么用 JdbcTemplate 而不是 MyBatis-Plus 的 BaseMapper</b>：
 * 这里的主力操作是「批量幂等 upsert + 旧数据不覆盖新数据」，
 * SQL 必须显式写成 {@code ON DUPLICATE KEY UPDATE ... IF(VALUES(fetched_at) >= fetched_at, ...)}。
 * 用 BaseMapper 还得再写一层 XML，反而多一处漂移点。读侧也全是显式列，
 * 不用 {@code SELECT *}——列漂移时希望它在 SQL 里可见，而不是在实体映射里静默。
 *
 * <p>所有读方法「查不到」返回 {@code null} / 空列表，<b>不返回 0</b>。
 * 数值字段一律 {@link BigDecimal}，缺失就是 null。
 */
@Repository
@RequiredArgsConstructor
public class WarehouseRepository {

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    // ==================================================================
    // 标的母表
    // ==================================================================

    public Optional<WarehouseRows.Instrument> findInstrument(String instrumentCode) {
        List<WarehouseRows.Instrument> rows = jdbcTemplate.query(
                "SELECT instrument_code, asset_type, market, raw_code, name, aliases, industry, "
                        + "industry_code, tracking_index_code, list_date, status, source, fetched_at "
                        + "FROM md_instrument WHERE instrument_code = ?",
                (rs, rowNum) -> instrument(rs), instrumentCode);
        return rows.stream().findFirst();
    }

    public Optional<WarehouseRows.Instrument> findInstrumentByRaw(String assetType, String rawCode) {
        List<WarehouseRows.Instrument> rows = jdbcTemplate.query(
                "SELECT instrument_code, asset_type, market, raw_code, name, aliases, industry, "
                        + "industry_code, tracking_index_code, list_date, status, source, fetched_at "
                        + "FROM md_instrument WHERE asset_type = ? AND raw_code = ?",
                (rs, rowNum) -> instrument(rs), assetType, rawCode);
        return rows.stream().findFirst();
    }

    public void upsertInstrument(WarehouseRows.Instrument row) {
        jdbcTemplate.update("""
                INSERT INTO md_instrument
                    (instrument_code, asset_type, market, raw_code, name, aliases, industry,
                     industry_code, tracking_index_code, list_date, status, source, fetched_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    market = COALESCE(VALUES(market), market),
                    name = COALESCE(VALUES(name), name),
                    aliases = COALESCE(VALUES(aliases), aliases),
                    industry = COALESCE(VALUES(industry), industry),
                    industry_code = COALESCE(VALUES(industry_code), industry_code),
                    tracking_index_code = COALESCE(VALUES(tracking_index_code), tracking_index_code),
                    list_date = COALESCE(VALUES(list_date), list_date),
                    status = COALESCE(VALUES(status), status),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """,
                row.instrumentCode(), row.assetType(), row.market(), row.rawCode(), row.name(),
                row.aliases(), row.industry(), row.industryCode(), row.trackingIndexCode(),
                row.listDate(), row.status(), row.source(), row.fetchedAt());
    }

    // ==================================================================
    // 行情
    // ==================================================================

    public Optional<WarehouseRows.PriceBar> latestPrice(String instrumentCode, String adjType) {
        List<WarehouseRows.PriceBar> rows = jdbcTemplate.query(
                "SELECT instrument_code, trade_date, adj_type, open_price, high_price, low_price, "
                        + "close_price, volume, amount, turnover_rate, pct_change, source, fetched_at "
                        + "FROM md_price_daily WHERE instrument_code = ? AND adj_type = ? "
                        + "ORDER BY trade_date DESC LIMIT 1",
                (rs, rowNum) -> priceBar(rs), instrumentCode, adjType);
        return rows.stream().findFirst();
    }

    /** 返回**按日期升序**的日线；内部先倒序取最近 N 根再反转。 */
    public List<WarehouseRows.PriceBar> latestPrices(String instrumentCode, String adjType, int limit) {
        int safeLimit = clampLimit(limit, 3000);
        List<WarehouseRows.PriceBar> rows = new ArrayList<>(jdbcTemplate.query(
                "SELECT instrument_code, trade_date, adj_type, open_price, high_price, low_price, "
                        + "close_price, volume, amount, turnover_rate, pct_change, source, fetched_at "
                        + "FROM md_price_daily WHERE instrument_code = ? AND adj_type = ? "
                        + "ORDER BY trade_date DESC LIMIT " + safeLimit,
                (rs, rowNum) -> priceBar(rs), instrumentCode, adjType));
        Collections.reverse(rows);
        return rows;
    }

    public List<WarehouseRows.PriceBar> pricesBetween(String instrumentCode, String adjType,
                                                      LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                "SELECT instrument_code, trade_date, adj_type, open_price, high_price, low_price, "
                        + "close_price, volume, amount, turnover_rate, pct_change, source, fetched_at "
                        + "FROM md_price_daily WHERE instrument_code = ? AND adj_type = ? "
                        + "AND trade_date BETWEEN ? AND ? ORDER BY trade_date ASC",
                (rs, rowNum) -> priceBar(rs), instrumentCode, adjType, from, to);
    }

    public void upsertPrices(List<WarehouseRows.PriceBar> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_price_daily
                    (instrument_code, trade_date, adj_type, open_price, high_price, low_price,
                     close_price, volume, amount, turnover_rate, pct_change, source, fetched_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    open_price = IF(VALUES(fetched_at) >= fetched_at, VALUES(open_price), open_price),
                    high_price = IF(VALUES(fetched_at) >= fetched_at, VALUES(high_price), high_price),
                    low_price = IF(VALUES(fetched_at) >= fetched_at, VALUES(low_price), low_price),
                    close_price = IF(VALUES(fetched_at) >= fetched_at, VALUES(close_price), close_price),
                    volume = IF(VALUES(fetched_at) >= fetched_at, VALUES(volume), volume),
                    amount = IF(VALUES(fetched_at) >= fetched_at, VALUES(amount), amount),
                    turnover_rate = IF(VALUES(fetched_at) >= fetched_at, VALUES(turnover_rate), turnover_rate),
                    pct_change = IF(VALUES(fetched_at) >= fetched_at, VALUES(pct_change), pct_change),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.PriceBar row : rows) {
            args.add(new Object[]{
                    row.instrumentCode(), row.tradeDate(), row.adjType(), row.openPrice(),
                    row.highPrice(), row.lowPrice(), row.closePrice(), row.volume(), row.amount(),
                    row.turnoverRate(), row.pctChange(), row.source(), row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    // ==================================================================
    // 估值
    // ==================================================================

    public Optional<WarehouseRows.ValuationPoint> latestValuation(String instrumentCode, String source) {
        List<WarehouseRows.ValuationPoint> rows = jdbcTemplate.query(
                "SELECT instrument_code, trade_date, pe_ttm, pb_mrq, ps_ttm, dividend_yield, "
                        + "close_price, percentile_method, source, fetched_at "
                        + "FROM md_valuation_daily WHERE instrument_code = ? AND source = ? "
                        + "ORDER BY trade_date DESC LIMIT 1",
                (rs, rowNum) -> valuation(rs), instrumentCode, source);
        return rows.stream().findFirst();
    }

    public List<WarehouseRows.ValuationPoint> valuationSeries(String instrumentCode, String source,
                                                              LocalDate from) {
        return jdbcTemplate.query(
                "SELECT instrument_code, trade_date, pe_ttm, pb_mrq, ps_ttm, dividend_yield, "
                        + "close_price, percentile_method, source, fetched_at "
                        + "FROM md_valuation_daily WHERE instrument_code = ? AND source = ? "
                        + "AND trade_date >= ? ORDER BY trade_date ASC",
                (rs, rowNum) -> valuation(rs), instrumentCode, source, from);
    }

    public void upsertValuations(List<WarehouseRows.ValuationPoint> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_valuation_daily
                    (instrument_code, trade_date, pe_ttm, pb_mrq, ps_ttm, dividend_yield,
                     close_price, percentile_method, source, fetched_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    pe_ttm = IF(VALUES(fetched_at) >= fetched_at, VALUES(pe_ttm), pe_ttm),
                    pb_mrq = IF(VALUES(fetched_at) >= fetched_at, VALUES(pb_mrq), pb_mrq),
                    ps_ttm = IF(VALUES(fetched_at) >= fetched_at, VALUES(ps_ttm), ps_ttm),
                    dividend_yield = IF(VALUES(fetched_at) >= fetched_at,
                                        VALUES(dividend_yield), dividend_yield),
                    close_price = IF(VALUES(fetched_at) >= fetched_at,
                                     VALUES(close_price), close_price),
                    percentile_method = COALESCE(VALUES(percentile_method), percentile_method),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.ValuationPoint row : rows) {
            args.add(new Object[]{
                    row.instrumentCode(), row.tradeDate(), row.peTtm(), row.pbMrq(), row.psTtm(),
                    row.dividendYield(), row.closePrice(), row.percentileMethod(), row.source(),
                    row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    // ==================================================================
    // 财务
    // ==================================================================

    public List<WarehouseRows.FinancialReport> financialsAvailableAsOf(String stockCode,
                                                                      LocalDate asOf, int limit) {
        int safeLimit = clampLimit(limit, 40);
        return jdbcTemplate.query(
                "SELECT stock_code, report_date, report_type, announce_date, available_from, "
                        + "revenue, revenue_yoy, net_profit, net_profit_yoy, roe, gross_margin, "
                        + "net_margin, ocf, total_assets, total_equity, total_liabilities, "
                        + "current_assets, current_liabilities, interest_expense, ebit, goodwill, "
                        + "accounts_receivable, source, fetched_at "
                        + "FROM md_financial_report WHERE stock_code = ? "
                        + "AND COALESCE(available_from, announce_date, report_date) <= ? "
                        + "ORDER BY report_date DESC LIMIT " + safeLimit,
                (rs, rowNum) -> financial(rs), stockCode, asOf);
    }

    public void upsertFinancials(List<WarehouseRows.FinancialReport> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_financial_report
                    (stock_code, report_date, report_type, announce_date, available_from,
                     revenue, revenue_yoy, net_profit, net_profit_yoy, roe, gross_margin, net_margin,
                     ocf, total_assets, total_equity, total_liabilities, current_assets,
                     current_liabilities, interest_expense, ebit, goodwill, accounts_receivable,
                     source, fetched_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    announce_date = COALESCE(VALUES(announce_date), announce_date),
                    available_from = COALESCE(VALUES(available_from), available_from),
                    revenue = IF(VALUES(fetched_at) >= fetched_at, VALUES(revenue), revenue),
                    revenue_yoy = IF(VALUES(fetched_at) >= fetched_at,
                                     VALUES(revenue_yoy), revenue_yoy),
                    net_profit = IF(VALUES(fetched_at) >= fetched_at,
                                    VALUES(net_profit), net_profit),
                    net_profit_yoy = IF(VALUES(fetched_at) >= fetched_at,
                                        VALUES(net_profit_yoy), net_profit_yoy),
                    roe = IF(VALUES(fetched_at) >= fetched_at, VALUES(roe), roe),
                    gross_margin = IF(VALUES(fetched_at) >= fetched_at,
                                      VALUES(gross_margin), gross_margin),
                    net_margin = IF(VALUES(fetched_at) >= fetched_at,
                                    VALUES(net_margin), net_margin),
                    ocf = IF(VALUES(fetched_at) >= fetched_at, VALUES(ocf), ocf),
                    total_assets = IF(VALUES(fetched_at) >= fetched_at,
                                      VALUES(total_assets), total_assets),
                    total_equity = IF(VALUES(fetched_at) >= fetched_at,
                                      VALUES(total_equity), total_equity),
                    total_liabilities = IF(VALUES(fetched_at) >= fetched_at,
                                           VALUES(total_liabilities), total_liabilities),
                    current_assets = IF(VALUES(fetched_at) >= fetched_at,
                                        VALUES(current_assets), current_assets),
                    current_liabilities = IF(VALUES(fetched_at) >= fetched_at,
                                             VALUES(current_liabilities), current_liabilities),
                    interest_expense = IF(VALUES(fetched_at) >= fetched_at,
                                          VALUES(interest_expense), interest_expense),
                    ebit = IF(VALUES(fetched_at) >= fetched_at, VALUES(ebit), ebit),
                    goodwill = IF(VALUES(fetched_at) >= fetched_at, VALUES(goodwill), goodwill),
                    accounts_receivable = IF(VALUES(fetched_at) >= fetched_at,
                                             VALUES(accounts_receivable), accounts_receivable),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.FinancialReport row : rows) {
            args.add(new Object[]{
                    row.stockCode(), row.reportDate(), row.reportType(), row.announceDate(),
                    row.availableFrom(), row.revenue(), row.revenueYoy(), row.netProfit(),
                    row.netProfitYoy(), row.roe(), row.grossMargin(), row.netMargin(), row.ocf(),
                    row.totalAssets(), row.totalEquity(), row.totalLiabilities(),
                    row.currentAssets(), row.currentLiabilities(), row.interestExpense(),
                    row.ebit(), row.goodwill(), row.accountsReceivable(), row.source(),
                    row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    // ==================================================================
    // 资金流
    // ==================================================================

    /** 按日期升序返回最近 N 个交易日。 */
    public List<WarehouseRows.MoneyFlow> moneyflow(String stockCode, int limit) {
        int safeLimit = clampLimit(limit, 120);
        List<WarehouseRows.MoneyFlow> rows = new ArrayList<>(jdbcTemplate.query(
                "SELECT stock_code, trade_date, main_net, super_net, large_net, medium_net, "
                        + "small_net, main_ratio, source, fetched_at "
                        + "FROM md_moneyflow_daily WHERE stock_code = ? "
                        + "ORDER BY trade_date DESC LIMIT " + safeLimit,
                (rs, rowNum) -> moneyflowRow(rs), stockCode));
        Collections.reverse(rows);
        return rows;
    }

    /** 按日期区间取资金流，升序。图表端点要 10 年时不能用上面那个「最近 120 条」。 */
    public List<WarehouseRows.MoneyFlow> moneyflowBetween(String stockCode,
                                                          LocalDate from, LocalDate to) {
        return jdbcTemplate.query(
                "SELECT stock_code, trade_date, main_net, super_net, large_net, medium_net, "
                        + "small_net, main_ratio, source, fetched_at "
                        + "FROM md_moneyflow_daily WHERE stock_code = ? "
                        + "AND trade_date BETWEEN ? AND ? ORDER BY trade_date ASC",
                (rs, rowNum) -> moneyflowRow(rs), stockCode, from, to);
    }

    public void upsertMoneyflows(List<WarehouseRows.MoneyFlow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_moneyflow_daily
                    (stock_code, trade_date, main_net, super_net, large_net, medium_net,
                     small_net, main_ratio, source, fetched_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    main_net = IF(VALUES(fetched_at) >= fetched_at, VALUES(main_net), main_net),
                    super_net = IF(VALUES(fetched_at) >= fetched_at, VALUES(super_net), super_net),
                    large_net = IF(VALUES(fetched_at) >= fetched_at, VALUES(large_net), large_net),
                    medium_net = IF(VALUES(fetched_at) >= fetched_at, VALUES(medium_net), medium_net),
                    small_net = IF(VALUES(fetched_at) >= fetched_at, VALUES(small_net), small_net),
                    main_ratio = IF(VALUES(fetched_at) >= fetched_at, VALUES(main_ratio), main_ratio),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.MoneyFlow row : rows) {
            args.add(new Object[]{
                    row.stockCode(), row.tradeDate(), row.mainNet(), row.superNet(), row.largeNet(),
                    row.mediumNet(), row.smallNet(), row.mainRatio(), row.source(), row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    // ==================================================================
    // 指数画像 / 权重 / 成分
    // ==================================================================

    public Optional<WarehouseRows.IndexProfile> latestIndexProfile(String indexCode) {
        List<WarehouseRows.IndexProfile> rows = jdbcTemplate.query(
                "SELECT index_code, as_of, name, constituent_count, top10_weight, pe_ttm, "
                        + "dividend_yield, total_market_cap, tracking_index_code, source, fetched_at "
                        + "FROM md_index_profile WHERE index_code = ? ORDER BY as_of DESC LIMIT 1",
                (rs, rowNum) -> indexProfile(rs), indexCode);
        return rows.stream().findFirst();
    }

    public List<WarehouseRows.IndustryWeight> latestIndustryWeights(String indexCode) {
        LocalDate asOf = latestAsOf("md_index_industry_weight", indexCode);
        if (asOf == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT index_code, as_of, industry_code, industry_name, weight, source, fetched_at "
                        + "FROM md_index_industry_weight WHERE index_code = ? AND as_of = ? "
                        + "ORDER BY weight DESC",
                (rs, rowNum) -> industryWeight(rs), indexCode, asOf);
    }

    public List<WarehouseRows.IndexConstituent> latestConstituents(String indexCode) {
        LocalDate asOf = latestAsOf("md_index_constituent", indexCode);
        if (asOf == null) {
            return List.of();
        }
        return jdbcTemplate.query(
                "SELECT index_code, as_of, stock_code, stock_name, weight, source, fetched_at "
                        + "FROM md_index_constituent WHERE index_code = ? AND as_of = ? "
                        + "ORDER BY weight DESC",
                (rs, rowNum) -> indexConstituent(rs), indexCode, asOf);
    }

    private LocalDate latestAsOf(String table, String indexCode) {
        // 表名来自本类内部常量（两个方法各传一个字面量），不是用户输入。
        List<LocalDate> rows = jdbcTemplate.query(
                "SELECT MAX(as_of) AS latest FROM " + table + " WHERE index_code = ?",
                (rs, rowNum) -> rs.getDate("latest") == null ? null : rs.getDate("latest").toLocalDate(),
                indexCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void upsertIndexProfiles(List<WarehouseRows.IndexProfile> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_index_profile
                    (index_code, as_of, name, constituent_count, top10_weight, pe_ttm,
                     dividend_yield, total_market_cap, tracking_index_code, source, fetched_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    name = COALESCE(VALUES(name), name),
                    constituent_count = IF(VALUES(fetched_at) >= fetched_at,
                                           VALUES(constituent_count), constituent_count),
                    top10_weight = IF(VALUES(fetched_at) >= fetched_at,
                                      VALUES(top10_weight), top10_weight),
                    pe_ttm = IF(VALUES(fetched_at) >= fetched_at, VALUES(pe_ttm), pe_ttm),
                    dividend_yield = IF(VALUES(fetched_at) >= fetched_at,
                                        VALUES(dividend_yield), dividend_yield),
                    total_market_cap = IF(VALUES(fetched_at) >= fetched_at,
                                          VALUES(total_market_cap), total_market_cap),
                    tracking_index_code = COALESCE(VALUES(tracking_index_code), tracking_index_code),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.IndexProfile row : rows) {
            args.add(new Object[]{
                    row.indexCode(), row.asOf(), row.name(), row.constituentCount(),
                    row.top10Weight(), row.peTtm(), row.dividendYield(), row.totalMarketCap(),
                    row.trackingIndexCode(), row.source(), row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    public void upsertIndustryWeights(List<WarehouseRows.IndustryWeight> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_index_industry_weight
                    (index_code, as_of, industry_code, industry_name, weight, source, fetched_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    industry_name = COALESCE(VALUES(industry_name), industry_name),
                    weight = IF(VALUES(fetched_at) >= fetched_at, VALUES(weight), weight),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.IndustryWeight row : rows) {
            args.add(new Object[]{
                    row.indexCode(), row.asOf(), row.industryCode(), row.industryName(),
                    row.weight(), row.source(), row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    public void upsertConstituents(List<WarehouseRows.IndexConstituent> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_index_constituent
                    (index_code, as_of, stock_code, stock_name, weight, source, fetched_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    stock_name = COALESCE(VALUES(stock_name), stock_name),
                    weight = IF(VALUES(fetched_at) >= fetched_at, VALUES(weight), weight),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.IndexConstituent row : rows) {
            args.add(new Object[]{
                    row.indexCode(), row.asOf(), row.stockCode(), row.stockName(), row.weight(),
                    row.source(), row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    // ==================================================================
    // 行业板块
    // ==================================================================

    public void upsertIndustryBoards(List<WarehouseRows.IndustryBoard> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_industry_board (board_code, name, source, fetched_at)
                VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    source = VALUES(source),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.IndustryBoard row : rows) {
            args.add(new Object[]{
                    row.boardCode(), row.name(), row.source(), row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    public void upsertIndustryConstituents(List<WarehouseRows.IndustryConstituent> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_industry_constituent
                    (board_code, stock_code, stock_name, as_of, source, fetched_at)
                VALUES (?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    stock_name = COALESCE(VALUES(stock_name), stock_name),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.IndustryConstituent row : rows) {
            args.add(new Object[]{
                    row.boardCode(), row.stockCode(), row.stockName(), row.asOf(), row.source(),
                    row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    // ==================================================================
    // 事件
    // ==================================================================

    /**
     * 该标的的事件 + 全局政策事件。政策事件的 {@code instrument_code} 为 NULL，
     * 由上层按行业名与标题再筛一次；这里不做语义判断。
     */
    public List<WarehouseRows.Event> events(String instrumentCode, int limit) {
        int safeLimit = clampLimit(limit, 200);
        return jdbcTemplate.query(
                "SELECT instrument_code, category, event_type, title, summary, url, published_at, "
                        + "source, importance, polarity, content_hash, fetched_at "
                        + "FROM md_event WHERE instrument_code = ? OR instrument_code IS NULL "
                        + "ORDER BY published_at DESC LIMIT " + safeLimit,
                (rs, rowNum) -> event(rs), instrumentCode);
    }

    public void upsertEvents(List<WarehouseRows.Event> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO md_event
                    (instrument_code, category, event_type, title, summary, url, published_at,
                     source, importance, polarity, content_hash, fetched_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    instrument_code = COALESCE(VALUES(instrument_code), instrument_code),
                    event_type = COALESCE(VALUES(event_type), event_type),
                    title = VALUES(title),
                    summary = COALESCE(VALUES(summary), summary),
                    url = COALESCE(VALUES(url), url),
                    published_at = COALESCE(VALUES(published_at), published_at),
                    importance = COALESCE(VALUES(importance), importance),
                    polarity = COALESCE(VALUES(polarity), polarity),
                    fetched_at = GREATEST(fetched_at, VALUES(fetched_at))
                """;
        List<Object[]> args = new ArrayList<>(rows.size());
        for (WarehouseRows.Event row : rows) {
            args.add(new Object[]{
                    row.instrumentCode(), row.category(), row.eventType(), row.title(),
                    row.summary(), row.url(), row.publishedAt(), row.source(), row.importance(),
                    row.polarity(), row.contentHash(), row.fetchedAt()
            });
        }
        batch(sql, args);
    }

    // ==================================================================
    // 解读缓存
    // ==================================================================

    /**
     * 读缓存。<b>过期行不返回</b>，由 SQL 的 {@code expires_at > ?} 判定，
     * 不依赖调用方时钟与数据库时钟一致。
     */
    public Optional<WarehouseRows.CachedAnalysis> findAnalysisCache(
            String instrumentCode, String snapshotHash, String rulesVersion,
            String promptVersion, LocalDateTime now) {
        List<WarehouseRows.CachedAnalysis> rows = jdbcTemplate.query(
                "SELECT instrument_code, snapshot_hash, rules_version, prompt_version, "
                        + "payload_json, created_at, expires_at "
                        + "FROM md_analysis_cache "
                        + "WHERE instrument_code = ? AND snapshot_hash = ? "
                        + "AND rules_version = ? AND prompt_version = ? AND expires_at > ?",
                (rs, rowNum) -> new WarehouseRows.CachedAnalysis(
                        rs.getString("instrument_code"), rs.getString("snapshot_hash"),
                        rs.getString("rules_version"), rs.getString("prompt_version"),
                        rs.getString("payload_json"),
                        localDateTime(rs, "created_at"), localDateTime(rs, "expires_at")),
                instrumentCode, snapshotHash, rulesVersion, promptVersion,
                Timestamp.valueOf(now));
        return rows.stream().findFirst();
    }

    /**
     * 写缓存。同一身份重复写就是覆盖：快照/版本没变时，新结果一定不比旧的差，
     * 而且让 {@code expires_at} 跟着刷新，避免命中一个刚要过期的条目。
     */
    public void upsertAnalysisCache(WarehouseRows.CachedAnalysis row) {
        jdbcTemplate.update("""
                INSERT INTO md_analysis_cache
                    (instrument_code, snapshot_hash, rules_version, prompt_version,
                     payload_json, created_at, expires_at)
                VALUES (?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE
                    payload_json = VALUES(payload_json),
                    created_at = VALUES(created_at),
                    expires_at = VALUES(expires_at)
                """,
                row.instrumentCode(), row.snapshotHash(), row.rulesVersion(),
                row.promptVersion(), row.payloadJson(), row.createdAt(), row.expiresAt());
    }

    // ==================================================================
    // 同步任务
    // ==================================================================

    public Optional<WarehouseRows.SyncJob> findJob(String jobKey) {
        List<WarehouseRows.SyncJob> rows = jdbcTemplate.query(
                "SELECT id, job_key, task, status, priority, instrument_code, payload_json, `cursor`, "
                        + "rows_affected, error, retry_after, started_at, finished_at "
                        + "FROM md_sync_job WHERE job_key = ?",
                (rs, rowNum) -> syncJob(rs), jobKey);
        return rows.stream().findFirst();
    }

    public Optional<WarehouseRows.SyncJob> latestJob(String instrumentCode, String task) {
        List<WarehouseRows.SyncJob> rows = jdbcTemplate.query(
                "SELECT id, job_key, task, status, priority, instrument_code, payload_json, `cursor`, "
                        + "rows_affected, error, retry_after, started_at, finished_at "
                        + "FROM md_sync_job WHERE instrument_code = ? AND task = ? "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> syncJob(rs), instrumentCode, task);
        return rows.stream().findFirst();
    }

    public void enqueueJob(String jobKey, String task, int priority, String instrumentCode,
                           String payloadJson) {
        jdbcTemplate.update("""
                INSERT INTO md_sync_job
                    (job_key, task, status, priority, instrument_code, payload_json)
                VALUES (?,?, 'PENDING', ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    task = VALUES(task),
                    priority = GREATEST(priority, VALUES(priority)),
                    instrument_code = COALESCE(VALUES(instrument_code), instrument_code),
                    payload_json = COALESCE(VALUES(payload_json), payload_json),
                    status = CASE
                        WHEN status = 'RUNNING' THEN status
                        WHEN status = 'DONE' THEN status
                        WHEN retry_after IS NOT NULL AND retry_after > NOW(3) THEN status
                        ELSE 'PENDING'
                    END
                """, jobKey, task, priority, instrumentCode, payloadJson);
    }

    public void markJobRunning(String jobKey) {
        jdbcTemplate.update(
                "UPDATE md_sync_job SET status='RUNNING', started_at=NOW(3), error=NULL "
                        + "WHERE job_key=? AND status IN ('PENDING','FAILED')", jobKey);
    }

    public void markJobDone(String jobKey, int rows) {
        jdbcTemplate.update(
                "UPDATE md_sync_job SET status='DONE', rows_affected=?, finished_at=NOW(3), "
                        + "retry_after=NULL, error=NULL WHERE job_key=?", rows, jobKey);
    }

    public void markJobFailed(String jobKey, String error, int retryAfterSeconds) {
        String safeError = error == null ? null
                : error.substring(0, Math.min(error.length(), 990));
        // 用 TIMESTAMPADD 而不是 `NOW() + INTERVAL ? SECOND`：后者的占位符在部分
        // MySQL 版本/驱动组合下会被当成非法 interval 表达式，而 TIMESTAMPADD
        // 的第二个参数就是普通整数，没有这个歧义。
        jdbcTemplate.update(
                "UPDATE md_sync_job SET status='FAILED', error=?, finished_at=NOW(3), "
                        + "retry_after=TIMESTAMPADD(SECOND, ?, NOW(3)) WHERE job_key=?",
                safeError, Math.max(0, retryAfterSeconds), jobKey);
    }

    // ==================================================================
    // 跨进程源状态
    // ==================================================================

    /**
     * 原子预约一个发车时刻。
     *
     * <p>用 {@code SELECT ... FOR UPDATE} 而不是「先读后写」：Java 与 Python 会同时
     * 打同一个源，两条「读-算-写」交错时，两边都会以为自己拿到了最早的时刻。
     *
     * <p>返回 {@code allowed=false} 时**没有推进 next_allowed_at**——
     * 排队超时的那次请求不许把后面所有人的发车时刻再往后推。
     */
    public Reservation reserve(String provider, long intervalMs, long maxWaitMs) {
        return jdbcTemplate.execute((org.springframework.jdbc.core.ConnectionCallback<Reservation>) connection -> {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                // 先确保行存在；INSERT IGNORE 不改变已有行。
                try (var insert = connection.prepareStatement(
                        "INSERT IGNORE INTO md_source_state (provider, updated_at) VALUES (?, NOW(3))")) {
                    insert.setString(1, provider);
                    insert.executeUpdate();
                }
                // **所有时间都取 DB 的 NOW(3)**，不用 JVM 的 clock。DATETIME 在库里是
                // 墙钟字面量，而应用与数据库未必同一时区；混用两边算出的「还要等多久」
                // 会整整差一个时区，表现成「冷却永远不结束」或「刚冷却就放行」。
                Timestamp dbNow;
                LocalDateTime nextAllowed;
                LocalDateTime cooldownUntil;
                try (var select = connection.prepareStatement(
                        "SELECT next_allowed_at, cooldown_until, NOW(3) AS db_now "
                                + "FROM md_source_state WHERE provider = ? FOR UPDATE")) {
                    select.setString(1, provider);
                    try (var rs = select.executeQuery()) {
                        if (!rs.next()) {
                            throw new IllegalStateException("md_source_state 行不存在: " + provider);
                        }
                        dbNow = rs.getTimestamp("db_now");
                        nextAllowed = toLocalDateTime(rs.getTimestamp("next_allowed_at"));
                        cooldownUntil = toLocalDateTime(rs.getTimestamp("cooldown_until"));
                    }
                }
                LocalDateTime now = dbNow.toLocalDateTime();
                if (cooldownUntil != null && cooldownUntil.isAfter(now)) {
                    connection.commit();
                    long remaining = java.time.Duration.between(now, cooldownUntil).toMillis();
                    return new Reservation(false, 0, true, remaining, "源在冷却期内");
                }
                LocalDateTime fireAt = nextAllowed != null && nextAllowed.isAfter(now) ? nextAllowed : now;
                long waitMs = java.time.Duration.between(now, fireAt).toMillis();
                if (waitMs > maxWaitMs) {
                    connection.commit();
                    return new Reservation(false, waitMs, false, 0,
                            "共享限速排队超时（" + provider + "）");
                }
                LocalDateTime next = fireAt.plusNanos(Math.max(0, intervalMs) * 1_000_000L);
                try (var update = connection.prepareStatement(
                        "UPDATE md_source_state SET next_allowed_at = ?, updated_at = NOW(3) "
                                + "WHERE provider = ?")) {
                    update.setTimestamp(1, Timestamp.valueOf(next));
                    update.setString(2, provider);
                    update.executeUpdate();
                }
                connection.commit();
                return new Reservation(true, Math.max(0, waitMs), false, 0, null);
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        });
    }

    /**
     * 冷却状态。**在 SQL 里用 {@code NOW(3)} 判**，不把时间取回 Java 再比——
     * 理由同 {@link #reserve}：应用与数据库可能不在同一时区。
     */
    public Optional<CooldownState> cooldownState(String provider) {
        List<CooldownState> rows = jdbcTemplate.query("""
                SELECT provider,
                       (cooldown_until IS NOT NULL AND cooldown_until > NOW(3)) AS cooling,
                       COALESCE(GREATEST(TIMESTAMPDIFF(MICROSECOND, NOW(3), cooldown_until), 0), 0)
                           AS remaining_us,
                       last_error
                FROM md_source_state WHERE provider = ?
                """,
                (rs, rowNum) -> new CooldownState(
                        rs.getString("provider"),
                        rs.getBoolean("cooling"),
                        rs.getLong("remaining_us") / 1000L,
                        rs.getString("last_error")),
                provider);
        return rows.stream().findFirst();
    }

    public Optional<WarehouseRows.SourceState> sourceState(String provider) {
        List<WarehouseRows.SourceState> rows = jdbcTemplate.query(
                "SELECT provider, next_allowed_at, cooldown_until, last_error "
                        + "FROM md_source_state WHERE provider = ?",
                (rs, rowNum) -> new WarehouseRows.SourceState(
                        rs.getString("provider"),
                        toLocalDateTime(rs.getTimestamp("next_allowed_at")),
                        toLocalDateTime(rs.getTimestamp("cooldown_until")),
                        rs.getString("last_error")),
                provider);
        return rows.stream().findFirst();
    }

    public void enterCooldown(String provider, long seconds, String reason) {
        String safeReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 490));
        long safeSeconds = Math.max(1, seconds);
        jdbcTemplate.update("""
                INSERT INTO md_source_state (provider, cooldown_until, last_error, updated_at)
                VALUES (?, TIMESTAMPADD(SECOND, ?, NOW(3)), ?, NOW(3))
                ON DUPLICATE KEY UPDATE
                    cooldown_until = GREATEST(
                        COALESCE(cooldown_until, NOW(3)),
                        TIMESTAMPADD(SECOND, ?, NOW(3))),
                    last_error = VALUES(last_error),
                    updated_at = NOW(3)
                """, provider, safeSeconds, safeReason, safeSeconds);
    }

    public void clearCooldown(String provider) {
        jdbcTemplate.update(
                "UPDATE md_source_state SET cooldown_until=NULL, updated_at=NOW(3) WHERE provider=?",
                provider);
    }

    // ==================================================================
    // 搜索（v2 搜索框）
    // ==================================================================

    /**
     * 按代码/名称/别名搜本地标的。
     *
     * <p>只查 {@code md_instrument}：搜索框是「找一个已经认识的代码」的入口，
     * 不应该为了一次输入去外呼全市场清单。库里没有就明说没有。
     *
     * <p>{@code %}/{@code _} 会被转义成字面量：用户输入 {@code %_%} 时应该得到
     * 「没有匹配」，而不是把全表端出来。
     */
    public List<WarehouseRows.Instrument> searchInstruments(String query, int limit) {
        int safeLimit = clampLimit(limit, 50);
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String q = query.strip();
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbcTemplate.query(
                "SELECT instrument_code, asset_type, market, raw_code, name, aliases, industry, "
                        + "industry_code, tracking_index_code, list_date, status, source, fetched_at "
                        + "FROM md_instrument "
                        + "WHERE raw_code LIKE ? OR name LIKE ? OR aliases LIKE ? "
                        + "ORDER BY CASE WHEN raw_code = ? THEN 0 WHEN raw_code LIKE ? THEN 1 ELSE 2 END, "
                        + "COALESCE(name, raw_code) ASC LIMIT " + safeLimit,
                (rs, rowNum) -> instrument(rs), like, like, like, q, q + "%");
    }

    // ==================================================================
    // 统计（健康检查 / 口径页）
    // ==================================================================

    public long countPrices(String instrumentCode) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM md_price_daily WHERE instrument_code = ?",
                Long.class, instrumentCode);
        return value == null ? 0 : value;
    }

    public String latestPriceDate(String instrumentCode) {
        return jdbcTemplate.queryForObject(
                "SELECT MAX(trade_date) FROM md_price_daily WHERE instrument_code = ?",
                String.class, instrumentCode);
    }

    // ==================================================================
    // 映射
    // ==================================================================

    private static WarehouseRows.Instrument instrument(ResultSet rs) throws SQLException {
        return new WarehouseRows.Instrument(
                rs.getString("instrument_code"), rs.getString("asset_type"), rs.getString("market"),
                rs.getString("raw_code"), rs.getString("name"), rs.getString("aliases"),
                rs.getString("industry"), rs.getString("industry_code"),
                rs.getString("tracking_index_code"), localDate(rs, "list_date"),
                rs.getString("status"), rs.getString("source"), localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.PriceBar priceBar(ResultSet rs) throws SQLException {
        return new WarehouseRows.PriceBar(
                rs.getString("instrument_code"), localDate(rs, "trade_date"),
                rs.getString("adj_type"), decimal(rs, "open_price"), decimal(rs, "high_price"),
                decimal(rs, "low_price"), decimal(rs, "close_price"), decimal(rs, "volume"),
                decimal(rs, "amount"), decimal(rs, "turnover_rate"), decimal(rs, "pct_change"),
                rs.getString("source"), localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.ValuationPoint valuation(ResultSet rs) throws SQLException {
        return new WarehouseRows.ValuationPoint(
                rs.getString("instrument_code"), localDate(rs, "trade_date"),
                decimal(rs, "pe_ttm"), decimal(rs, "pb_mrq"), decimal(rs, "ps_ttm"),
                decimal(rs, "dividend_yield"), decimal(rs, "close_price"),
                rs.getString("percentile_method"), rs.getString("source"),
                localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.FinancialReport financial(ResultSet rs) throws SQLException {
        return new WarehouseRows.FinancialReport(
                rs.getString("stock_code"), localDate(rs, "report_date"),
                rs.getString("report_type"), localDate(rs, "announce_date"),
                localDate(rs, "available_from"), decimal(rs, "revenue"),
                decimal(rs, "revenue_yoy"), decimal(rs, "net_profit"),
                decimal(rs, "net_profit_yoy"), decimal(rs, "roe"), decimal(rs, "gross_margin"),
                decimal(rs, "net_margin"), decimal(rs, "ocf"), decimal(rs, "total_assets"),
                decimal(rs, "total_equity"), decimal(rs, "total_liabilities"),
                decimal(rs, "current_assets"), decimal(rs, "current_liabilities"),
                decimal(rs, "interest_expense"), decimal(rs, "ebit"), decimal(rs, "goodwill"),
                decimal(rs, "accounts_receivable"), rs.getString("source"),
                localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.MoneyFlow moneyflowRow(ResultSet rs) throws SQLException {
        return new WarehouseRows.MoneyFlow(
                rs.getString("stock_code"), localDate(rs, "trade_date"),
                decimal(rs, "main_net"), decimal(rs, "super_net"), decimal(rs, "large_net"),
                decimal(rs, "medium_net"), decimal(rs, "small_net"), decimal(rs, "main_ratio"),
                rs.getString("source"), localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.IndexProfile indexProfile(ResultSet rs) throws SQLException {
        return new WarehouseRows.IndexProfile(
                rs.getString("index_code"), localDate(rs, "as_of"), rs.getString("name"),
                (Integer) rs.getObject("constituent_count"), decimal(rs, "top10_weight"),
                decimal(rs, "pe_ttm"), decimal(rs, "dividend_yield"),
                decimal(rs, "total_market_cap"), rs.getString("tracking_index_code"),
                rs.getString("source"), localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.IndustryWeight industryWeight(ResultSet rs) throws SQLException {
        return new WarehouseRows.IndustryWeight(
                rs.getString("index_code"), localDate(rs, "as_of"),
                rs.getString("industry_code"), rs.getString("industry_name"),
                decimal(rs, "weight"), rs.getString("source"),
                localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.IndexConstituent indexConstituent(ResultSet rs) throws SQLException {
        return new WarehouseRows.IndexConstituent(
                rs.getString("index_code"), localDate(rs, "as_of"),
                rs.getString("stock_code"), rs.getString("stock_name"),
                decimal(rs, "weight"), rs.getString("source"),
                localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.Event event(ResultSet rs) throws SQLException {
        return new WarehouseRows.Event(
                rs.getString("instrument_code"), rs.getString("category"),
                rs.getString("event_type"), rs.getString("title"), rs.getString("summary"),
                rs.getString("url"), localDateTime(rs, "published_at"), rs.getString("source"),
                rs.getString("importance"), rs.getString("polarity"),
                rs.getString("content_hash"), localDateTime(rs, "fetched_at"));
    }

    private static WarehouseRows.SyncJob syncJob(ResultSet rs) throws SQLException {
        return new WarehouseRows.SyncJob(
                rs.getLong("id"), rs.getString("job_key"), rs.getString("task"),
                rs.getString("status"), rs.getInt("priority"), rs.getString("instrument_code"),
                rs.getString("payload_json"), rs.getString("cursor"),
                (Integer) rs.getObject("rows_affected"), rs.getString("error"),
                localDateTime(rs, "retry_after"), localDateTime(rs, "started_at"),
                localDateTime(rs, "finished_at"));
    }

    private void batch(String sql, List<Object[]> args) {
        for (int start = 0; start < args.size(); start += BATCH_SIZE) {
            int end = Math.min(args.size(), start + BATCH_SIZE);
            jdbcTemplate.batchUpdate(sql, args.subList(start, end));
        }
    }

    private static int clampLimit(int limit, int max) {
        if (limit <= 0) {
            return 1;
        }
        return Math.min(limit, max);
    }

    private static BigDecimal decimal(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    private static LocalDate localDate(ResultSet rs, String column) throws SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws SQLException {
        return toLocalDateTime(rs.getTimestamp(column));
    }

    private static LocalDateTime toLocalDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    /**
     * 共享闸门的一次预约结果。
     *
     * @param allowed           是否拿到发车资格
     * @param waitMs            需要等待的毫秒数
     * @param cooling           是否处于冷却期
     * @param cooldownRemainingMs 冷却剩余毫秒
     * @param reason            不允许时的原因
     */
    public record Reservation(boolean allowed, long waitMs, boolean cooling,
                              long cooldownRemainingMs, String reason) {
    }

    /**
     * 冷却快照。
     *
     * @param cooling          此刻是否处于冷却期（由 DB 的 NOW(3) 判定）
     * @param remainingMs      冷却剩余毫秒；不在冷却期时为 0
     * @param lastError        写入冷却时记下的原因
     */
    public record CooldownState(String provider, boolean cooling, long remainingMs,
                                String lastError) {
    }
}
