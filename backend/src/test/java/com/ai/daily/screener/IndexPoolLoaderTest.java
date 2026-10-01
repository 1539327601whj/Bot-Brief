package com.ai.daily.screener;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 指数池加载器。
 *
 * <p>池子是**人手工维护、提交进仓库**的文件，所以这里的重点不是「能读」，而是
 * **读不懂时能不能立刻炸掉**。一条写错的池子如果在运行期表现成「某只指数悄悄缺测」，
 * 那就退化成了用户已经抱怨过的「空着的场景」——而且在页面上看不出是配置错了还是行情没回来。
 *
 * <p>所以每一条校验都对应一种**真的会犯的错**：字段名拼错、类别写了个没定义的值、
 * 两个指数抢同一个 ETF、估值来源填了一半。
 */
class IndexPoolLoaderTest {

    /**
     * 每条指数**写成一行**。文本块会剥掉各行的共同缩进，跨行写法会让下面那些
     * {@code replace} 的目标串到底有几个空格变得很脆——写成一行的就没这个问题。
     */
    private static final String OK = """
            {"indices":[
            {"indexCode":"SH000300","indexName":"沪深300","category":"broad","valuationSource":"csindex","percentileMethod":"CSI_PE_TTM_ROLLING_10Y","csindexCode":"000300","etfCode":"510300","market":1,"etfName":"沪深300ETF","tracking":"沪深300"},
            {"indexCode":"SH000905","indexName":"中证500","category":"broad","valuationSource":null,"percentileMethod":null,"csindexCode":null,"etfCode":"510500","market":1,"etfName":"中证500ETF","tracking":"中证500"}
            ]}""";

    private static List<IndexFundPool.Fund> load(String json) {
        return IndexFundPool.parseAndValidate(json.getBytes(StandardCharsets.UTF_8));
    }

    // ================= 正常 =================

    @Test
    void loadsAGoodPoolAndSplitsIndicesByWhetherTheyHaveAnEtf() {
        IndexFundPool pool = new IndexFundPool(new ClassPathResource("screener/index-pool.json"));

        assertThat(pool.funds()).isNotEmpty();
        // 出货的池子里每一条都必须有代表 ETF，否则价格位置与规模会全是空的
        assertThat(pool.withEtf()).hasSameSizeAs(pool.funds());
        assertThat(pool.funds()).allSatisfy(f -> assertThat(f.hasEtf()).isTrue());
    }

    @Test
    void theShippedPoolStillCarriesTheSevenOriginalBroadIndices() {
        IndexFundPool pool = new IndexFundPool(new ClassPathResource("screener/index-pool.json"));

        assertThat(pool.withEtf()).extracting(IndexFundPool.Fund::etfCode)
                .contains("510300", "510500", "510050", "159915", "588000", "512100", "510880");
    }

    @Test
    void keepsFieldOrderingAndDerivesSecidFromMarketPlusCode() {
        List<IndexFundPool.Fund> funds = load(OK);

        assertThat(funds).hasSize(2);
        assertThat(funds.get(0).secid()).isEqualTo("1.510300");
        assertThat(funds.get(0).indexCode()).isEqualTo("SH000300");
        assertThat(funds.get(1).hasValuation()).isFalse();
        assertThat(funds.get(1).secid()).isEqualTo("1.510500");
    }

    @Test
    void anIndexWithoutAnEtfHasNoSecidSoCallersMustNotConcatenateBlindly() {
        List<IndexFundPool.Fund> funds = load("""
                {"indices":[{"indexCode":"SH000300","indexName":"沪深300","category":"broad",
                 "valuationSource":null,"percentileMethod":null,"csindexCode":null,
                 "etfCode":null,"market":null,"etfName":null,"tracking":"沪深300"}]}""");

        assertThat(funds.get(0).hasEtf()).isFalse();
        assertThat(funds.get(0).secid()).isNull();
    }

    // ================= 该炸的必须炸 =================

    @Test
    void anUnknownFieldNameFailsInsteadOfSilentlyProducingAnEmptyPool() {
        // csindexCode 拼成 csindexcode —— 静默忽略的话这条指数的估值会永远不生效
        assertThatThrownBy(() -> load(OK.replace("\"csindexCode\":\"000300\"",
                        "\"csindexcode\":\"000300\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是合法 JSON，或含未知字段")
                .hasMessageContaining("csindexcode");
    }

    @Test
    void aCategoryOutsideTheEnumFails() {
        assertThatThrownBy(() -> load(OK.replace("\"category\":\"broad\",\"valuationSource\":\"csindex\"",
                        "\"category\":\"宽基\",\"valuationSource\":\"csindex\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不在");
    }

    @Test
    void aDuplicatedIndexCodeFails() {
        assertThatThrownBy(() -> load(OK.replace("\"indexCode\":\"SH000905\"", "\"indexCode\":\"SH000300\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indexCode 重复");
    }

    @Test
    void twoIndicesSharingOneEtfFails() {
        // 同一个 ETF 不可能代表两个指数。放过去的话两张卡会显示同一只基金的价格
        assertThatThrownBy(() -> load(OK.replace("\"etfCode\":\"510500\"", "\"etfCode\":\"510300\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("共用同一个 etfCode");
    }

    @Test
    void aMethodThatDoesNotMatchItsSourceFails() {
        assertThatThrownBy(() -> load(OK.replace("\"CSI_PE_TTM_ROLLING_10Y\"", "\"DANJUAN_PE_TTM_PROVIDER\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("percentileMethod 不是 CSI_PE_TTM_ROLLING_10Y");
    }

    @Test
    void anUnknownValuationSourceFails() {
        assertThatThrownBy(() -> load(OK.replace("\"csindex\"", "\"xueqiu\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不是 csindex 或 danjuan");
    }

    @Test
    void aCsindexEntryWithoutItsCodeFails() {
        assertThatThrownBy(() -> load(OK.replace("\"csindexCode\":\"000300\"", "\"csindexCode\":null")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("缺 csindexCode");
    }

    @Test
    void aCodeWithoutASourceFailsBecauseThatEntryWouldNeverTakeEffect() {
        // 改了池子但忘了填 valuationSource：不报的话这条指数永远显示「未接入」，
        // 而维护者以为自己已经接上了
        assertThatThrownBy(() -> load(OK.replace(
                        "\"valuationSource\":null,\"percentileMethod\":null,\"csindexCode\":null",
                        "\"valuationSource\":null,\"percentileMethod\":null,\"csindexCode\":\"000905\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("永远不会生效");
    }

    @Test
    void anEtfWithoutAMarketFails() {
        assertThatThrownBy(() -> load(OK.replace("\"etfCode\":\"510500\",\"market\":1", "\"etfCode\":\"510500\"")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("market");
    }

    @Test
    void marketOrEtfNameWithoutAnEtfCodeFails() {
        assertThatThrownBy(() -> load(OK.replace("\"etfCode\":\"510500\",\"market\":1",
                        "\"etfCode\":null,\"market\":1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("要么都给要么都不给");
    }

    @Test
    void anEmptyPoolFails() {
        assertThatThrownBy(() -> load("{\"indices\":[]}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("indices 为空");
    }

    @Test
    void aWholeFileIsReportedWithEveryProblemAtOnceNotJustTheFirst() {
        // 一次加好几条时，一条一条试太慢——所有问题要一次列全
        assertThatThrownBy(() -> load(OK
                        .replace("\"indexCode\":\"SH000905\"", "\"indexCode\":\"SH000300\"")
                        .replace("\"category\":\"broad\",\"valuationSource\":null",
                                "\"category\":\"宽基\",\"valuationSource\":null")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 处不合规")
                .hasMessageContaining("indexCode 重复")
                .hasMessageContaining("不在");
    }

    @Test
    void listingLookupsAreAvailableByBothKeys() {
        // 池子里 indexCode 是库里的键、etfCode 是行情源的键，两边都要能查
        IndexFundPool pool = new IndexFundPool(new ClassPathResource("screener/index-pool.json"));

        assertThat(pool.byIndexCode("SH000300")).isNotNull();
        assertThat(pool.byIndexCode("SH000300").etfCode()).isEqualTo("510300");
        assertThat(pool.byEtfCode("510300").indexCode()).isEqualTo("SH000300");
        assertThat(pool.byEtfCode("999999")).isNull();
    }
}