package com.ai.daily.screener;

import com.ai.daily.entity.ScreenerScanHistory;
import com.ai.daily.mapper.ScreenerScanHistoryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 「低估精选」筛选历史：管理员每次成功筛选落一行，可回看、可整页回放。
 *
 * <p><b>这里只做记录，不做判定。**写得再花也不会影响筛选结果本身**：调用方
 * （{@code StockScreenerController}）把 {@link #record} 的异常吞在自己的 catch 里，
 * 只记 warning 并把这件事写进返回体的降级说明，绝不让一次写库失败把结果变成错误页。
 *
 * <p><b>为什么放在 {@code screener} 包而不是 {@code service} 包</b>：它吃的是
 * {@link ScreenerResultDTO}，与 {@code ScreenerPrefetchService} 同理——包内协作的
 * 编排件，实体与 mapper 仍留在通用的 {@code entity} / {@code mapper} 包里。
 *
 * <p><b>用的是 Spring 管的 {@link ObjectMapper}，不是 {@code new ObjectMapper()}。</b>
 * 结果快照里有 {@code LocalDate} / {@code LocalDateTime}（{@code valuationTradeDate}、
 * {@code lastTradeDate}），而项目里那几个手搓的 mapper（行情解析用的）没注册
 * {@code JavaTimeModule}，拿它们序列化会直接抛 {@code InvalidDefinitionException}。
 */
@Slf4j
@Service
public class ScreenerHistoryService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** {@code MybatisPlusConfig} 的分页拦截器上限是 50，这里再挡一次，避免静默被截断。 */
    static final int MAX_PAGE_SIZE = 50;

    private final ScreenerScanHistoryMapper mapper;
    private final ObjectMapper objectMapper;
    private final int keep;

    public ScreenerHistoryService(ScreenerScanHistoryMapper mapper,
                                  ObjectMapper objectMapper,
                                  @Value("${screener.history.keep:100}") int keep) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        // 0 或负数会让裁剪变成「一行都不留」，那是把历史静默清空——下限兜到 1
        this.keep = Math.max(1, keep);
    }

    /**
     * 落一行历史。
     *
     * <p>插入失败**往外抛**（由调用方决定怎么告诉用户）；裁剪失败只记 warning——
     * 裁剪是维护动作，它失败的结果只是表继续长，没有任何理由因此丢掉一条刚写好的记录。
     *
     * <p>刻意不加 {@code @Transactional}：一旦加上，裁剪那步抛异常会把插入一起回滚，
     * 于是「表满了」这种无关痛痒的问题会吃掉一条本该留下的审计记录。
     */
    public void record(ScreenerParams applied, ScreenerResultDTO result, Long userId, String email) {
        ScreenerScanHistory row = new ScreenerScanHistory();
        // 与结果里的 dataTime 是同一次请求内取的墙钟，差在毫秒级；这里不解析那个字符串，
        // 免得结果里那串格式一改就整条历史写不进去
        row.setScannedAt(LocalDateTime.now(SHANGHAI));
        row.setScannedByUserId(userId);
        row.setScannedByEmail(email == null || email.isBlank() ? null : email);
        row.setMode(applied.getMode());
        row.setBucket(applied.getBucket());
        row.setPerBucket(applied.getPerBucket());
        row.setParamsJson(writeJson(applied));

        ScreenerResultDTO.Summary s = result.summary();
        row.setScannedCount(s.scanned());
        row.setAfterVetoes(s.afterVetoes());
        row.setSteadyPool(s.steadyPool());
        row.setGrowthPool(s.growthPool());
        row.setShortlistFetched(s.shortlistFetched());

        List<ScreenerHistoryDTO.Ref> indices = new ArrayList<>();
        int qualified = 0;
        for (ScreenerResultDTO.IndexFundItem f : result.indexFunds()) {
            boolean ok = "qualified".equals(f.qualification());
            if (ok) qualified++;
            indices.add(new ScreenerHistoryDTO.Ref(f.indexCode(), f.indexName(), ok));
        }
        row.setIndexCount(result.indexFunds().size());
        row.setQualifiedIndexCount(qualified);

        row.setSelectionJson(writeJson(new ScreenerHistoryDTO.Selection(
                indices,
                stockRefs(result.steadyStocks()),
                stockRefs(result.growthStocks()))));
        row.setResultJson(writeJson(result));

        mapper.insert(row);
        trim();
    }

    /** 列表。**逐列挑，不把 {@code result_json} 拉进内存**：那个字段几十 KB，十行就是几百 KB。 */
    public Page<ScreenerScanHistory> page(int page, int size) {
        Page<ScreenerScanHistory> p = new Page<>(Math.max(1, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
        LambdaQueryWrapper<ScreenerScanHistory> q = new LambdaQueryWrapper<ScreenerScanHistory>()
                .select(ScreenerScanHistory::getId, ScreenerScanHistory::getScannedAt,
                        ScreenerScanHistory::getScannedByEmail, ScreenerScanHistory::getMode,
                        ScreenerScanHistory::getBucket, ScreenerScanHistory::getPerBucket,
                        ScreenerScanHistory::getParamsJson, ScreenerScanHistory::getScannedCount,
                        ScreenerScanHistory::getAfterVetoes, ScreenerScanHistory::getSteadyPool,
                        ScreenerScanHistory::getGrowthPool, ScreenerScanHistory::getShortlistFetched,
                        ScreenerScanHistory::getIndexCount, ScreenerScanHistory::getQualifiedIndexCount,
                        ScreenerScanHistory::getSelectionJson, ScreenerScanHistory::getCreatedAt)
                // 同一秒内跑两次（限流后立刻重试）可能同 scannedAt，用 id 兜底才有稳定顺序
                .orderByDesc(ScreenerScanHistory::getScannedAt)
                .orderByDesc(ScreenerScanHistory::getId);
        return mapper.selectPage(p, q);
    }

    /** 详情：列表项 + 可整页回放的结果 JSON。 */
    public Optional<ScreenerHistoryDTO.Detail> detail(long id) {
        ScreenerScanHistory row = mapper.selectById(id);
        if (row == null) return Optional.empty();
        try {
            return Optional.of(new ScreenerHistoryDTO.Detail(
                    toItem(row), objectMapper.readTree(row.getResultJson())));
        } catch (JsonProcessingException e) {
            // 库里那串坏了才走到这里。不吞：让控制器报 500，页面显示「这次回放失败」
            throw new IllegalStateException("筛选历史的结果快照解析失败 id=" + id, e);
        }
    }

    public ScreenerHistoryDTO.Item toItem(ScreenerScanHistory row) {
        ScreenerHistoryDTO.Selection selection = new ScreenerHistoryDTO.Selection(List.of(), List.of(), List.of());
        if (row.getSelectionJson() != null && !row.getSelectionJson().isBlank()) {
            try {
                selection = objectMapper.readValue(row.getSelectionJson(),
                        new TypeReference<ScreenerHistoryDTO.Selection>() {});
            } catch (JsonProcessingException e) {
                // 入选清单坏掉不该让整条历史打不开：列表项照出，清单留空
                log.warn("筛选历史入选清单解析失败 id={}：{}", row.getId(), e.toString());
            }
        }
        ScreenerParams applied = null;
        if (row.getParamsJson() != null && !row.getParamsJson().isBlank()) {
            try {
                applied = objectMapper.readValue(row.getParamsJson(), ScreenerParams.class);
            } catch (JsonProcessingException e) {
                log.warn("筛选历史参数解析失败 id={}：{}", row.getId(), e.toString());
            }
        }
        return new ScreenerHistoryDTO.Item(
                row.getId(),
                row.getScannedAt() == null ? null : row.getScannedAt().format(TS),
                row.getScannedByEmail(),
                row.getMode(), row.getBucket(),
                row.getPerBucket() == null ? 0 : row.getPerBucket(),
                n(row.getScannedCount()), n(row.getAfterVetoes()),
                n(row.getSteadyPool()), n(row.getGrowthPool()), n(row.getShortlistFetched()),
                n(row.getIndexCount()), n(row.getQualifiedIndexCount()),
                selection.indices(), selection.steadyStocks(), selection.growthStocks(),
                applied);
    }

    private void trim() {
        try {
            long oldestKept = mapper.oldestKeptId(keep);
            int deleted = mapper.deleteOlderThan(oldestKept);
            if (deleted > 0) {
                log.info("筛选历史裁剪 {} 条，保留最近 {} 条", deleted, keep);
            }
        } catch (RuntimeException e) {
            log.warn("筛选历史裁剪失败（本次记录已写入，只是表会继续增长）：{}", e.toString());
        }
    }

    private List<ScreenerHistoryDTO.Ref> stockRefs(List<ScreeningRules.Selected> selected) {
        List<ScreenerHistoryDTO.Ref> out = new ArrayList<>();
        for (ScreeningRules.Selected s : selected) {
            // 个股没有「合格」这个概念：能出现在这里就是被选中的
            out.add(new ScreenerHistoryDTO.Ref(s.row().getCode(), s.row().getName(), true));
        }
        return out;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("筛选历史序列化失败", e);
        }
    }

    private static int n(Integer v) {
        return v == null ? 0 : v;
    }
}