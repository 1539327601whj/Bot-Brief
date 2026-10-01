package com.ai.daily.service;

import com.ai.daily.dto.EtfPriceHistoryIngestDTO;
import com.ai.daily.entity.EtfPriceHistory;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDate;
import java.util.List;

public interface EtfPriceHistoryService extends IService<EtfPriceHistory> {

    /**
     * 唯一支持的复权口径。价格位置的每个数（分位、回撤、年化波动）都建立在
     * 「曲线内部口径一致」这个前提上，所以这里**只认一个值**，不给第二种选择。
     */
    String QFQ = "QFQ";

    void upsertBatch(List<EtfPriceHistoryIngestDTO> prices);

    List<EtfPriceHistory> latest(String fundCode, int limit, String adjustmentType);

    /**
     * 批量读一批基金的日线，给「指数池」那种几十上百只的场景用。
     *
     * <p>返回的行是**按 {@code fund_code}、{@code trade_date} 升序**的原始多源数据，
     * 一天可能有两行（东财一行、腾讯一行）。**调用方必须自己选源**
     * （{@link com.ai.daily.screener.EtfPriceSeriesSelector}），
     * 直接按日期铺开当成一根曲线用，会在换源处静默算错。
     *
     * @param fundCodes      基金代码；空列表返回空列表，**不查库**（`IN ()` 是语法错误）
     * @param from           交易日起点（含）。取不到就短一截，不会因此报错
     * @param adjustmentType 只支持 {@link #QFQ}
     */
    List<EtfPriceHistory> latestBatch(List<String> fundCodes, LocalDate from, String adjustmentType);
}