package com.ai.daily.screener;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 指数池：**单位是指数，不是 ETF**。ETF 只是取价格位置与规模的**手段**。
 *
 * <p>池子定义在 {@code backend/src/main/resources/screener/index-pool.json}，本类在启动时加载。
 * Python 侧通过 {@code GET /api/index-pool} 读同一份，**不在 {@code automation/} 下另存一份**
 * ——两边各存一份必然漂移。
 *
 * <p><b>故意严格。</b>池子是提交进仓库、由人手工维护的，字段名拼错、类别写了个没定义的值、
 * 同一天两个指数抢同一个 ETF——这些都不该在运行时以「某只指数悄悄缺测」的形式表现。
 * 所以 {@code FAIL_ON_UNKNOWN_PROPERTIES} 是开的，任何一条不合规都**直接让应用起不来**。
 * 这是有意选的：宁可不说，不可编造。
 *
 * <p><b>每个指数只钉一个估值来源。</b>{@code csindex}（中证官网）与 {@code danjuan}（蛋卷）
 * 不是同一口径——实测中证500 差 22%、科创50 差 75%。同一个指数今天取这个源明天取那个源，
 * 分位会跳变而页面上看不出来。可比性由 {@code valuationSource} 承载，不由方法名承载；
 * 因此分位排序只允许发生在**同一来源的子组内部**。规则见
 * {@code automation/agents/stock_screening_rules.md} §5.3。
 */
@Slf4j
@Component
public class IndexFundPool {

    /**
     * 一条指数。{@code valuationSource} 为空 = 该指数没有 PE 分位数据源；页面上的说法由
     * {@code StockScreenerService} 给出（卡片的状态串），**不在这里存一句现成的话**——
     * 那样会把「为什么缺 + 下一步怎样」拆到两个文件里。
     *
     * <p>{@code indexCode} 是**项目库里的键**（带 {@code SH}/{@code SZ} 前缀，对应
     * {@code market_valuation_history.index_code}）；{@code csindexCode} 是**中证官网接口的入参**
     * （6 位）。两者分开存，是因为它们不是一回事——哪天不同步，混成一个字段就查不出来。
     */
    public record Fund(
            String indexCode,
            String indexName,
            String category,
            String valuationSource,
            String percentileMethod,
            String csindexCode,
            String danjuanCode,
            String etfCode,
            Integer market,
            String etfName,
            String tracking
    ) {
        /** 没有代表 ETF 时返回 null——调用方**必须先判空**，别直接拼进 secids。 */
        public String secid() {
            return etfCode == null ? null : market + "." + etfCode;
        }

        public boolean hasEtf() {
            return etfCode != null;
        }

        public boolean hasValuation() {
            return valuationSource != null;
        }
    }

    /** JSON 顶层。单独的包装对象是为了以后能加版本号而不破坏已发布的池子。 */
    private record PoolFile(List<Fund> indices) {}

    public static final String SOURCE_CSINDEX = "csindex";
    public static final String SOURCE_DANJUAN = "danjuan";
    public static final String METHOD_CSINDEX_ROLLING_10Y = "CSI_PE_TTM_ROLLING_10Y";
    public static final String METHOD_DANJUAN = "DANJUAN_PE_TTM_PROVIDER";

    static final Set<String> CATEGORIES =
            Set.of("broad", "strategy", "sector", "theme", "overseas", "other");

    /** 中证官网的入参代码：6 位数字，或 {@code H30533} 这种带字母的。 */
    private static final Pattern CSINDEX_CODE = Pattern.compile("[0-9A-Za-z]{6}");

    private static final String ILLEGAL = "指数池 index-pool.json 非法：";

    private final List<Fund> funds;
    private final List<Fund> withEtf;
    private final Map<String, Fund> byIndexCode;
    private final Map<String, Fund> byEtfCode;

    public IndexFundPool(@Value("classpath:screener/index-pool.json") Resource resource) {
        this(parseAndValidate(readBytes(resource)));
    }

    private IndexFundPool(List<Fund> funds) {
        this.funds = List.copyOf(funds);

        List<Fund> etf = new ArrayList<>();
        Map<String, Fund> byIndex = new LinkedHashMap<>();
        Map<String, Fund> byEtf = new LinkedHashMap<>();
        for (Fund f : this.funds) {
            if (f.hasEtf()) {
                etf.add(f);
                byEtf.put(f.etfCode(), f);
            }
            byIndex.put(f.indexCode(), f);
        }
        this.withEtf = Collections.unmodifiableList(etf);
        this.byIndexCode = Collections.unmodifiableMap(byIndex);
        this.byEtfCode = Collections.unmodifiableMap(byEtf);

        log.info("指数池加载完成：共 {} 个指数，其中 {} 个有代表 ETF，{} 个已接入估值来源",
                this.funds.size(), this.withEtf.size(),
                this.funds.stream().filter(Fund::hasValuation).count());
    }

    /** 池子里全部指数，含没有 ETF 的。 */
    public List<Fund> funds() {
        return funds;
    }

    /** 只含**有代表 ETF** 的指数——价格位置与规模取得到的那批。 */
    public List<Fund> withEtf() {
        return withEtf;
    }

    public Fund byIndexCode(String indexCode) {
        return byIndexCode.get(indexCode);
    }

    public Fund byEtfCode(String etfCode) {
        return byEtfCode.get(etfCode);
    }

    // ------------------------------------------------------------------
    // 加载与校验
    // ------------------------------------------------------------------

    private static byte[] readBytes(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(ILLEGAL + "读不到 classpath:screener/index-pool.json", e);
        }
    }

    /**
     * 解析并逐条校验。**任何一条不合规都抛 {@link IllegalStateException}，让应用起不来。**
     *
     * <p>一次把所有问题都列出来（最多 {@value #MAX_REPORTED} 条），而不是报第一条就停——
     * 改池子的人通常一次加好几条，一条一条试太慢。
     */
    static List<Fund> parseAndValidate(byte[] json) {
        if (json == null || json.length == 0) {
            throw new IllegalStateException(ILLEGAL + "文件是空的");
        }

        // 显式打开：默认就是 true，但这一条是故意的设计，不该被别处的 ObjectMapper 配置改掉。
        ObjectMapper mapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        PoolFile file;
        try {
            file = mapper.readValue(json, PoolFile.class);
        } catch (IOException e) {
            throw new IllegalStateException(ILLEGAL
                    + "不是合法 JSON，或含未知字段（字段名拼错会在这里报出来）：" + e.getMessage(), e);
        }

        List<Fund> funds = file.indices();
        if (funds == null || funds.isEmpty()) {
            throw new IllegalStateException(ILLEGAL + "indices 为空");
        }

        List<String> errors = new ArrayList<>();
        Map<String, Integer> indexCodeAt = new LinkedHashMap<>();
        Map<String, Integer> etfCodeAt = new LinkedHashMap<>();

        for (int i = 0; i < funds.size(); i++) {
            Fund f = funds.get(i);
            String where = "indices[" + i + "]" + (f.indexCode() == null ? "" : "(" + f.indexCode() + ")");

            require(errors, where + " 缺 indexCode", isBlank(f.indexCode()));
            require(errors, where + " 缺 indexName", isBlank(f.indexName()));
            require(errors, where + " category=" + f.category() + " 不在 " + CATEGORIES,
                    f.category() == null || !CATEGORIES.contains(f.category()));

            if (!isBlank(f.indexCode())) {
                Integer prev = indexCodeAt.putIfAbsent(f.indexCode(), i);
                if (prev != null) {
                    errors.add(where + " 与 indices[" + prev + "] 的 indexCode 重复");
                }
            }

            // ---- 代表 ETF：有就一起给全，没有就都别给 ----
            if (f.hasEtf()) {
                require(errors, where + " 有 etfCode 但 market 不是 0/1（market=" + f.market() + "）",
                        f.market() == null || (f.market() != 0 && f.market() != 1));
                Integer prev = etfCodeAt.putIfAbsent(f.etfCode(), i);
                if (prev != null) {
                    errors.add(where + " 与 indices[" + prev + "] 共用同一个 etfCode="
                            + f.etfCode() + "，一个 ETF 只能代表一个指数");
                }
            } else if (f.market() != null || !isBlank(f.etfName())) {
                errors.add(where + " 没有 etfCode 却给了 market/etfName，二者要么都给要么都不给");
            }

            // ---- 估值来源：三个字段必须自洽 ----
            String src = f.valuationSource();
            if (src == null) {
                // 填了代码却没填来源 = 有人改了一半。不报的话这条会永远静默不生效。
                if (!isBlank(f.percentileMethod()) || !isBlank(f.csindexCode()) || !isBlank(f.danjuanCode())) {
                    errors.add(where + " valuationSource 为空，却填了 percentileMethod/csindexCode/danjuanCode"
                            + "——这条永远不会生效，要么补上 valuationSource，要么把这些字段清掉");
                }
            } else if (SOURCE_CSINDEX.equals(src)) {
                require(errors, where + " valuationSource=csindex 但 percentileMethod 不是 "
                                + METHOD_CSINDEX_ROLLING_10Y + "（实际 " + f.percentileMethod() + "）",
                        !METHOD_CSINDEX_ROLLING_10Y.equals(f.percentileMethod()));
                require(errors, where + " valuationSource=csindex 缺 csindexCode", isBlank(f.csindexCode()));
                require(errors, where + " csindexCode=" + f.csindexCode() + " 不是 6 位代码",
                        !isBlank(f.csindexCode()) && !CSINDEX_CODE.matcher(f.csindexCode()).matches());
            } else if (SOURCE_DANJUAN.equals(src)) {
                require(errors, where + " valuationSource=danjuan 但 percentileMethod 不是 "
                                + METHOD_DANJUAN + "（实际 " + f.percentileMethod() + "）",
                        !METHOD_DANJUAN.equals(f.percentileMethod()));
                require(errors, where + " valuationSource=danjuan 缺 danjuanCode", isBlank(f.danjuanCode()));
            } else {
                errors.add(where + " valuationSource=" + src + " 不是 " + SOURCE_CSINDEX
                        + " 或 " + SOURCE_DANJUAN);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(ILLEGAL + errors.size() + " 处不合规：\n  - "
                    + String.join("\n  - ", errors.subList(0, Math.min(errors.size(), MAX_REPORTED))));
        }
        return funds;
    }

    private static final int MAX_REPORTED = 20;

    private static void require(List<String> errors, String message, boolean violated) {
        if (violated) errors.add(message);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}