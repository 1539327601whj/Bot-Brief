package com.ai.daily.screener;

import com.ai.daily.entity.ScreenerScanHistory;
import com.ai.daily.mapper.ScreenerScanHistoryMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 筛选历史落库。
 *
 * <p>这一层刻意不做任何业务判定，测试的重点因此是三件容易被写坏的事：
 * <ol>
 *   <li>写进去的摘要列要与结果对得上（列表页只读这些列，读错了没人会发现）；</li>
 *   <li>整页快照要能真的存下来——里面带 {@link LocalDate}，用手搓的
 *       {@code new ObjectMapper()} 会当场抛异常（见
 *       {@link #aMapperWithoutJavaTimeCannotSerializeTheSnapshotAtAll()}）；</li>
 *   <li>写失败与裁剪失败要分开：前者往外抛，后者只记 warning。</li>
 * </ol>
 */
class ScreenerHistoryServiceTest {

    private ScreenerScanHistoryMapper mapper;

    /** 与 Spring 管的那只对齐：注册了 JavaTime 与参数名模块，记录才能序列化/反序列化。 */
    private final ObjectMapper json = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .addModule(new ParameterNamesModule())
            .build();

    /**
     * 列表那条查询是用 lambda 拼的列名，MP 要靠实体上的 {@code @TableName} 建一份
     * 「lambda → 列名」的缓存——那是 Spring 启动时扫 mapper 干的事，单测里没有。
     * 不预先把这份缓存建好，连 {@code LambdaQueryWrapper.select(...)} 都构造不出来。
     */
    @BeforeAll
    static void registerTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), ScreenerScanHistory.class);
    }

    @BeforeEach
    void setUp() {
        mapper = mock(ScreenerScanHistoryMapper.class);
    }

    private ScreenerHistoryService service() {
        return new ScreenerHistoryService(mapper, json, 100);
    }

    // ================= 落库 =================

    @Test
    void oneRowCarriesTheAppliedParamsTheSummaryAndTheSelection() {
        ScreenerParams applied = new ScreenerParams();
        applied.setMode(ScreenerParams.MODE_INDEX_FIRST);
        applied.setBucket(ScreenerParams.BUCKET_BOTH);
        applied.setPerBucket(5);
        applied.setPePercentileMax(new BigDecimal("30"));
        applied = applied.normalized();

        service().record(applied, result(), 7L, "admin@example.com");

        ScreenerScanHistory row = capturedRow();
        // 时间必须是写入时刻的墙钟，用户要靠它对齐「我几点点的」
        assertThat(row.getScannedAt()).isNotNull().isBefore(LocalDateTime.now().plusMinutes(1));
        assertThat(row.getScannedByUserId()).isEqualTo(7L);
        assertThat(row.getScannedByEmail()).isEqualTo("admin@example.com");
        assertThat(row.getMode()).isEqualTo("index_first");
        assertThat(row.getBucket()).isEqualTo("both");
        assertThat(row.getPerBucket()).isEqualTo(5);
        assertThat(row.getScannedCount()).isEqualTo(300);
        assertThat(row.getAfterVetoes()).isEqualTo(12);
        assertThat(row.getSteadyPool()).isEqualTo(5);
        assertThat(row.getGrowthPool()).isEqualTo(7);
        assertThat(row.getShortlistFetched()).isEqualTo(16);

        // 合格数按 qualification 字段数，而不是「返回了几张卡」
        assertThat(row.getIndexCount()).isEqualTo(3);
        assertThat(row.getQualifiedIndexCount()).isEqualTo(1);
    }

    @Test
    void theStoredParamsCanBeReadBackToRefillTheConditionPanel() throws Exception {
        ScreenerParams applied = new ScreenerParams();
        applied.setPePercentileMax(new BigDecimal("30"));
        applied = applied.normalized();

        service().record(applied, result(), 1L, "admin@example.com");

        ScreenerParams back = json.readValue(capturedRow().getParamsJson(), ScreenerParams.class);
        // 存的是**解析默认值之后**的那一套：回填面板时要看到当时真正生效的条件
        assertThat(back.getMode()).isEqualTo("index_first");
        assertThat(back.getPerBucket()).isEqualTo(3);
        assertThat(back.getPePercentileMax()).isEqualByComparingTo("30");
    }

    @Test
    void theResultSnapshotRoundTripsIncludingTheDates() throws Exception {
        service().record(ScreenerParams.defaults(), result(), 1L, "admin@example.com");

        ScreenerResultDTO back = json.readValue(capturedRow().getResultJson(), ScreenerResultDTO.class);

        assertThat(back.dataTime()).isEqualTo("2026-10-02 10:24:15");
        assertThat(back.priceAsOf()).isEqualTo("2026-09-30");
        assertThat(back.indexFunds()).hasSize(3);
        // 这一对 LocalDate 就是「必须用 Spring 那只 ObjectMapper」的原因
        assertThat(back.indexFunds().get(0).valuationTradeDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(back.indexFunds().get(0).lastTradeDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(back.indexFunds().get(1).qualification()).isEqualTo("blocked");
        assertThat(back.indexFunds().get(1).qualificationReasons()).hasSize(1);
    }

    /**
     * 把「为什么构造函数非要那只 Spring 的 ObjectMapper」钉成一条测试。
     *
     * <p>项目里几个解析行情的 mapper 是手搓的 {@code new ObjectMapper()}，没注册
     * {@code JavaTimeModule}；历史服务要是图省事也那么建，表现是**每次筛选都写不进历史**，
     * 而且只在运行时炸。这条断言就是为了后人换实现时能立刻看到代价。
     */
    @Test
    void aMapperWithoutJavaTimeCannotSerializeTheSnapshotAtAll() {
        assertThatThrownBy(() -> new ObjectMapper().writeValueAsString(result()))
                .isInstanceOf(InvalidDefinitionException.class);
    }

    @Test
    void aBlankEmailIsStoredAsNullRatherThanAnEmptyString() {
        service().record(ScreenerParams.defaults(), result(), null, "   ");
        assertThat(capturedRow().getScannedByEmail()).isNull();
        assertThat(capturedRow().getScannedByUserId()).isNull();
    }

    // ================= 失败路径 =================

    @Test
    void aFailedInsertPropagatesSoTheCallerCanTellTheUser() {
        when(mapper.insert(any())).thenThrow(new RuntimeException("表不存在"));

        assertThatThrownBy(() -> service().record(ScreenerParams.defaults(), result(), 1L, "a@b.c"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("表不存在");
    }

    /**
     * 裁剪是维护动作：它失败的结果只是表继续变长，没有任何理由因此丢掉一条刚写好的记录。
     * （服务的裁剪方法自带 try/catch，不加 {@code @Transactional} 也是为这个——
     * 加了事务，裁剪抛异常会把插入一起回滚。）
     */
    @Test
    void aFailedTrimDoesNotFailTheRecord() {
        when(mapper.oldestKeptId(anyInt())).thenThrow(new RuntimeException("锁等待超时"));

        service().record(ScreenerParams.defaults(), result(), 1L, "a@b.c");

        verify(mapper).insert(any());
    }

    @Test
    void keepIsFlooredAtOneSoTheHistoryCannotBeSilentlyWiped() {
        new ScreenerHistoryService(mapper, json, 0)
                .record(ScreenerParams.defaults(), result(), 1L, "a@b.c");

        // 0 会算成「保留最新 0 条」，等于每次写完全清空
        verify(mapper).oldestKeptId(1);
    }

    // ================= 列表 =================

    @Test
    @SuppressWarnings("unchecked")
    void theListQueryClampsPagingAndNeverPullsTheResultSnapshot() {
        when(mapper.selectPage(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        Page<ScreenerScanHistory> returned = service().page(0, 999);

        // MybatisPlusConfig 的拦截器上限是 50：这里再挡一次，免得「静默截到 50」被当成正常
        assertThat(returned.getCurrent()).isEqualTo(1);
        assertThat(returned.getSize()).isEqualTo(50);

        ArgumentCaptor<LambdaQueryWrapper<ScreenerScanHistory>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        String selected = captor.getValue().getSqlSelect();

        // 逐列挑：result_json 一行几十 KB，十行就是几百 KB，列表页读不起
        assertThat(selected).contains("scanned_at").doesNotContain("result_json");
    }

    // ================= 详情 =================

    @Test
    void anUnknownIdIsEmptyRatherThanAnException() {
        when(mapper.selectById(9L)).thenReturn(null);
        assertThat(service().detail(9L)).isEmpty();
    }

    @Test
    void theDetailCarriesTheListItemAndTheWholeSnapshot() {
        ScreenerScanHistory row = capturedRowAfterOneRecord();

        Optional<ScreenerHistoryDTO.Detail> d = service().detail(row.getId());

        assertThat(d).isPresent();
        ScreenerHistoryDTO.Item item = d.get().item();
        assertThat(item.scannedAt()).isEqualTo(row.getScannedAt().format(
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertThat(item.qualifiedIndexCount()).isEqualTo(1);
        // 入选清单单独存了一份，列表页不必解析几十 KB 的快照
        assertThat(item.indices()).extracting(ScreenerHistoryDTO.Ref::code)
                .containsExactly("000300", "399006", "000905");
        assertThat(item.indices()).extracting(ScreenerHistoryDTO.Ref::qualified)
                .containsExactly(true, false, false);
        assertThat(item.appliedParams().getMode()).isEqualTo("index_first");
        // 详情多带一件：整页回放的原始结果
        assertThat(d.get().result().get("dataTime").asText()).isEqualTo("2026-10-02 10:24:15");
    }

    @Test
    void aCorruptSnapshotIsReportedAsABugRatherThanAnEmptyPage() {
        ScreenerScanHistory row = capturedRowAfterOneRecord();
        row.setResultJson("{ 这不是 JSON");

        assertThatThrownBy(() -> service().detail(row.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("结果快照解析失败");
    }

    /**
     * 清单或参数串坏掉**不该**让整条历史打不开：列表项照出，坏掉的那部分留空。
     * 这是刻意的宽松——历史是给人看的台账，多显示一行半成品比整页 500 好。
     */
    @Test
    void aCorruptSelectionOrParamsStillYieldsAReadableRow() {
        ScreenerScanHistory row = capturedRowAfterOneRecord();
        row.setSelectionJson("坏掉的清单");
        row.setParamsJson("坏掉的参数");
        when(mapper.selectById(1L)).thenReturn(row);

        ScreenerHistoryDTO.Item item = service().detail(1L).orElseThrow().item();

        assertThat(item.indices()).isEmpty();
        assertThat(item.steadyStocks()).isEmpty();
        assertThat(item.appliedParams()).isNull();
        // 摘要列不依赖那两串 JSON，照常给出
        assertThat(item.scanned()).isEqualTo(300);
    }

    // ================= 夹具 =================

    private ScreenerScanHistory capturedRow() {
        ArgumentCaptor<ScreenerScanHistory> captor = ArgumentCaptor.forClass(ScreenerScanHistory.class);
        verify(mapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 先真走一遍 record，再把那一行当成库里的行喂回详情，避免手抄两份夹具。 */
    private ScreenerScanHistory capturedRowAfterOneRecord() {
        service().record(ScreenerParams.defaults(), result(), 1L, "admin@example.com");
        ScreenerScanHistory row = capturedRow();
        row.setId(1L);
        when(mapper.selectById(1L)).thenReturn(row);
        return row;
    }

    private static ScreenerResultDTO result() {
        return new ScreenerResultDTO(
                "2026-10-02 10:24:15", "2026-09-30",
                ScreenerParams.defaults(),
                new ScreenerResultDTO.Summary(300, 12, 5, 7, 16, List.of(), List.of(), List.of()),
                List.of(
                        index("000300", "沪深300", "qualified", "27.5", List.of()),
                        index("399006", "创业板指", "blocked", "61.2",
                                List.of("PE 分位 61.2% > 上限 30")),
                        index("000905", "中证500", "unconfirmed", null,
                                List.of("PE 分位暂无记录"))),
                List.of(), List.of(),
                "本页仅为公开行情数据的规则化筛选结果，不构成投资建议。");
    }

    /** 有值即无状态串：与 {@code IndexFundItem} 的缺值不变式保持一致。 */
    private static ScreenerResultDTO.IndexFundItem index(
            String code, String name, String qualification, String pePercentile, List<String> reasons) {
        return new ScreenerResultDTO.IndexFundItem(
                code, name, "broad", code + "ETF", name + "ETF", name,
                new BigDecimal("3.85"), new BigDecimal("0.42"),
                new BigDecimal("12.6"), new BigDecimal("653.84"),
                new BigDecimal("41.2"), new BigDecimal("6.1"),
                new BigDecimal("18.4"), new BigDecimal("15.2"), new BigDecimal("2.3"),
                new BigDecimal("13.15"), pePercentile == null ? null : new BigDecimal(pePercentile),
                null, null, null, null, null, null,
                pePercentile == null ? "PE 分位暂无记录" : null,
                "腾讯", "腾讯", "中证指数官网", "近十年 PE(TTM) 分位",
                LocalDate.of(2026, 9, 30), 250, LocalDate.of(2026, 9, 30),
                List.of("PE 分位是【指数】的口径，不是该 ETF 自身的分位；不同估值来源不可横向比较"),
                qualification, reasons);
    }
}