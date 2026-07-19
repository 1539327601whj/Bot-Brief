package com.ai.daily.service.impl;

import com.ai.daily.dto.*;
import com.ai.daily.entity.*;
import com.ai.daily.mapper.*;
import com.ai.daily.service.ShopAnalyticsService;
import com.ai.daily.service.ShopStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ShopAnalyticsServiceImpl implements ShopAnalyticsService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

    private final ShopStoreService shopStoreService;
    private final ShopProductMapper productMapper;
    private final ShopSalesDailyMapper salesDailyMapper;
    private final ShopProductSalesDailyMapper productSalesDailyMapper;
    private final ShopCustomerSummaryMapper customerSummaryMapper;
    private final ShopAiReportMapper aiReportMapper;
    private final ObjectMapper objectMapper;

    @Override
    public void generateDemoData(Long userId, Long storeId) {
        ShopStore store = requireStore(userId, storeId);
        List<ShopProduct> products = ensureDemoProducts(userId, store);
        LocalDate today = LocalDate.now();

        for (int i = 29; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            BigDecimal totalSales = ZERO;
            int totalOrders = 0;
            int totalBuyers = 0;

            for (int p = 0; p < products.size(); p++) {
                ShopProduct product = products.get(p);
                int sold = demoQuantity(p, i);
                BigDecimal sales = product.getPrice().multiply(BigDecimal.valueOf(sold)).setScale(2, RoundingMode.HALF_UP);
                int orders = Math.max(0, sold - (sold / 5));
                int stock = Math.max(0, product.getStock() - sold + i * (p % 3));

                upsertProductSales(userId, store.getId(), product.getId(), date, sales, orders, sold, stock);
                totalSales = totalSales.add(sales);
                totalOrders += orders;
                totalBuyers += Math.max(0, orders - (orders / 8));
            }

            upsertSalesDaily(userId, store.getId(), date, totalSales, totalOrders, totalBuyers);
            upsertCustomerSummary(userId, store.getId(), date, totalSales, totalOrders, i);
        }
    }

    @Override
    public ShopOverviewDTO getOverview(Long userId, Long storeId, int range) {
        ShopStore store = requireStore(userId, storeId);
        int safeRange = Math.max(7, Math.min(range, 30));
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(safeRange - 1L);

        List<ShopSalesDaily> sales = salesDailyMapper.selectList(new LambdaQueryWrapper<ShopSalesDaily>()
                .eq(ShopSalesDaily::getUserId, userId)
                .eq(ShopSalesDaily::getStoreId, store.getId())
                .ge(ShopSalesDaily::getStatDate, startDate)
                .le(ShopSalesDaily::getStatDate, today)
                .orderByAsc(ShopSalesDaily::getStatDate));
        List<ShopProduct> products = productMapper.selectList(new LambdaQueryWrapper<ShopProduct>()
                .eq(ShopProduct::getUserId, userId)
                .eq(ShopProduct::getStoreId, store.getId()));
        List<ShopProductSalesDaily> productSales = productSalesDailyMapper.selectList(new LambdaQueryWrapper<ShopProductSalesDaily>()
                .eq(ShopProductSalesDaily::getUserId, userId)
                .eq(ShopProductSalesDaily::getStoreId, store.getId())
                .ge(ShopProductSalesDaily::getStatDate, startDate)
                .le(ShopProductSalesDaily::getStatDate, today));
        ShopCustomerSummary customer = customerSummaryMapper.selectOne(new LambdaQueryWrapper<ShopCustomerSummary>()
                .eq(ShopCustomerSummary::getUserId, userId)
                .eq(ShopCustomerSummary::getStoreId, store.getId())
                .eq(ShopCustomerSummary::getStatDate, today)
                .last("LIMIT 1"));

        List<ShopProductRankDTO> hotProducts = buildHotProducts(products, productSales);
        List<ShopProductRankDTO> slowProducts = buildSlowProducts(products, productSales);
        List<ShopReplenishmentSuggestionDTO> replenishment = buildReplenishment(products, productSales);
        ShopCustomerProfileDTO customerProfile = buildCustomerProfile(customer);

        ShopOverviewDTO dto = new ShopOverviewDTO();
        dto.setToday(buildTodayMetrics(sales, customerProfile));
        dto.setHotProducts(hotProducts);
        dto.setSlowProducts(slowProducts);
        dto.setCustomers(customerProfile);
        dto.setSalesTrend(buildSalesTrend(sales));
        dto.setReplenishmentSuggestions(replenishment);
        dto.setActivitySuggestions(buildActivitySuggestions(dto.getToday(), hotProducts, slowProducts, replenishment));
        dto.setAiReport(getLatestAiReport(userId, store.getId()));
        return dto;
    }

    @Override
    public ShopAiReportDTO generateAiReport(Long userId, Long storeId) {
        ShopStore store = requireStore(userId, storeId);
        ShopOverviewDTO overview = getOverview(userId, store.getId(), 7);
        LocalDate today = LocalDate.now();
        String title = today + " 店铺经营日报";
        String content = buildReportContent(title, overview);
        String summary = buildReportSummary(overview);

        ShopAiReport existing = aiReportMapper.selectOne(new LambdaQueryWrapper<ShopAiReport>()
                .eq(ShopAiReport::getUserId, userId)
                .eq(ShopAiReport::getStoreId, store.getId())
                .eq(ShopAiReport::getReportDate, today)
                .last("LIMIT 1"));
        ShopAiReport report = existing == null ? new ShopAiReport() : existing;
        report.setUserId(userId);
        report.setStoreId(store.getId());
        report.setReportDate(today);
        report.setTitle(title);
        report.setSummary(summary);
        report.setContent(content);
        report.setRiskLevel(hasHighRisk(overview) ? "warning" : "normal");
        report.setGeneratedBy("rule");
        if (report.getId() == null) {
            aiReportMapper.insert(report);
        } else {
            aiReportMapper.updateById(report);
        }
        return toAiReportDTO(report);
    }

    @Override
    public ShopAiReportDTO getLatestAiReport(Long userId, Long storeId) {
        ShopStore store = requireStore(userId, storeId);
        ShopAiReport report = aiReportMapper.selectOne(new LambdaQueryWrapper<ShopAiReport>()
                .eq(ShopAiReport::getUserId, userId)
                .eq(ShopAiReport::getStoreId, store.getId())
                .orderByDesc(ShopAiReport::getReportDate)
                .last("LIMIT 1"));
        return report == null ? null : toAiReportDTO(report);
    }

    private ShopStore requireStore(Long userId, Long storeId) {
        ShopStore store = storeId == null ? shopStoreService.getOrCreateDefault(userId) : shopStoreService.getForUser(userId, storeId);
        if (store == null) throw new IllegalArgumentException("店铺不存在");
        return store;
    }

    private List<ShopProduct> ensureDemoProducts(Long userId, ShopStore store) {
        List<ShopProduct> existing = productMapper.selectList(new LambdaQueryWrapper<ShopProduct>()
                .eq(ShopProduct::getUserId, userId)
                .eq(ShopProduct::getStoreId, store.getId()));
        if (!existing.isEmpty()) return existing;

        String[][] seed = {
                {"夏季防晒冰丝衫", "女装", "89.00", "180"},
                {"高腰阔腿牛仔裤", "女装", "129.00", "120"},
                {"便携榨汁杯", "小家电", "99.00", "95"},
                {"儿童护眼台灯", "家居", "169.00", "80"},
                {"通勤托特包", "箱包", "139.00", "160"},
                {"旧款加绒外套", "女装", "199.00", "260"},
                {"基础款帆布鞋", "鞋靴", "79.00", "220"},
                {"厨房收纳套装", "家居", "59.00", "140"},
                {"美妆蛋礼盒", "美妆", "39.00", "180"},
                {"运动速干短袖", "运动", "69.00", "130"}
        };
        List<ShopProduct> products = new ArrayList<>();
        for (String[] item : seed) {
            ShopProduct product = new ShopProduct();
            product.setUserId(userId);
            product.setStoreId(store.getId());
            product.setPlatform(store.getPlatform());
            product.setProductName(item[0]);
            product.setCategory(item[1]);
            product.setPrice(new BigDecimal(item[2]));
            product.setStock(Integer.parseInt(item[3]));
            product.setStatus("active");
            productMapper.insert(product);
            products.add(product);
        }
        return products;
    }

    private int demoQuantity(int productIndex, int dayOffsetFromToday) {
        int wave = (30 - dayOffsetFromToday) % 7;
        if (productIndex <= 1) return 18 + wave * 2 + productIndex * 3;
        if (productIndex == 2 || productIndex == 3) return 8 + wave + productIndex;
        if (productIndex == 5 || productIndex == 6) return dayOffsetFromToday < 7 ? 1 : 2;
        return 4 + (wave + productIndex) % 6;
    }

    private void upsertSalesDaily(Long userId, Long storeId, LocalDate date, BigDecimal sales, int orders, int buyers) {
        ShopSalesDaily existing = salesDailyMapper.selectOne(new LambdaQueryWrapper<ShopSalesDaily>()
                .eq(ShopSalesDaily::getUserId, userId)
                .eq(ShopSalesDaily::getStoreId, storeId)
                .eq(ShopSalesDaily::getStatDate, date)
                .last("LIMIT 1"));
        ShopSalesDaily row = existing == null ? new ShopSalesDaily() : existing;
        row.setUserId(userId);
        row.setStoreId(storeId);
        row.setStatDate(date);
        row.setSalesAmount(sales);
        row.setOrderCount(orders);
        row.setBuyerCount(buyers);
        row.setRefundAmount(sales.multiply(new BigDecimal("0.03")).setScale(2, RoundingMode.HALF_UP));
        if (row.getId() == null) salesDailyMapper.insert(row); else salesDailyMapper.updateById(row);
    }

    private void upsertProductSales(Long userId, Long storeId, Long productId, LocalDate date, BigDecimal sales, int orders, int sold, int stock) {
        ShopProductSalesDaily existing = productSalesDailyMapper.selectOne(new LambdaQueryWrapper<ShopProductSalesDaily>()
                .eq(ShopProductSalesDaily::getUserId, userId)
                .eq(ShopProductSalesDaily::getStoreId, storeId)
                .eq(ShopProductSalesDaily::getProductId, productId)
                .eq(ShopProductSalesDaily::getStatDate, date)
                .last("LIMIT 1"));
        ShopProductSalesDaily row = existing == null ? new ShopProductSalesDaily() : existing;
        row.setUserId(userId);
        row.setStoreId(storeId);
        row.setProductId(productId);
        row.setStatDate(date);
        row.setSalesAmount(sales);
        row.setOrderCount(orders);
        row.setQuantitySold(sold);
        row.setStock(stock);
        if (row.getId() == null) productSalesDailyMapper.insert(row); else productSalesDailyMapper.updateById(row);
    }

    private void upsertCustomerSummary(Long userId, Long storeId, LocalDate date, BigDecimal sales, int orders, int dayOffset) {
        ShopCustomerSummary existing = customerSummaryMapper.selectOne(new LambdaQueryWrapper<ShopCustomerSummary>()
                .eq(ShopCustomerSummary::getUserId, userId)
                .eq(ShopCustomerSummary::getStoreId, storeId)
                .eq(ShopCustomerSummary::getStatDate, date)
                .last("LIMIT 1"));
        ShopCustomerSummary row = existing == null ? new ShopCustomerSummary() : existing;
        row.setUserId(userId);
        row.setStoreId(storeId);
        row.setStatDate(date);
        row.setNewCustomerCount(Math.max(8, orders / 3));
        row.setRepeatCustomerCount(Math.max(5, orders / 4));
        row.setHighValueCustomerCount(Math.max(3, orders / 12));
        row.setAvgCustomerValue(orders == 0 ? ZERO : sales.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP));
        row.setFemaleRatio(new BigDecimal("62.00"));
        row.setMaleRatio(new BigDecimal("38.00"));
        row.setTopRegions("[{\"name\":\"广东\",\"value\":" + (32 + dayOffset % 5) + "},{\"name\":\"浙江\",\"value\":24},{\"name\":\"江苏\",\"value\":18},{\"name\":\"四川\",\"value\":14},{\"name\":\"山东\",\"value\":12}]");
        row.setAgeDistribution("[{\"name\":\"18-24\",\"value\":18},{\"name\":\"25-34\",\"value\":46},{\"name\":\"35-44\",\"value\":24},{\"name\":\"45+\",\"value\":12}]");
        if (row.getId() == null) customerSummaryMapper.insert(row); else customerSummaryMapper.updateById(row);
    }

    private ShopTodayMetricsDTO buildTodayMetrics(List<ShopSalesDaily> sales, ShopCustomerProfileDTO customer) {
        LocalDate today = LocalDate.now();
        Map<LocalDate, ShopSalesDaily> byDate = sales.stream().collect(Collectors.toMap(ShopSalesDaily::getStatDate, Function.identity()));
        ShopSalesDaily todayRow = byDate.get(today);
        ShopSalesDaily yesterdayRow = byDate.get(today.minusDays(1));

        BigDecimal salesAmount = todayRow == null ? ZERO : nvl(todayRow.getSalesAmount());
        int orderCount = todayRow == null ? 0 : nvl(todayRow.getOrderCount());
        int buyerCount = todayRow == null ? 0 : nvl(todayRow.getBuyerCount());

        ShopTodayMetricsDTO dto = new ShopTodayMetricsDTO();
        dto.setSalesAmount(salesAmount);
        dto.setOrderCount(orderCount);
        dto.setBuyerCount(buyerCount);
        dto.setAverageOrderValue(orderCount == 0 ? ZERO : salesAmount.divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP));
        dto.setSalesChangeRate(changeRate(salesAmount, yesterdayRow == null ? ZERO : nvl(yesterdayRow.getSalesAmount())));
        dto.setOrderChangeRate(changeRate(BigDecimal.valueOf(orderCount), BigDecimal.valueOf(yesterdayRow == null ? 0 : nvl(yesterdayRow.getOrderCount()))));
        dto.setRepeatCustomerCount(customer.getRepeatCustomerCount());
        return dto;
    }

    private List<ShopSalesTrendDTO> buildSalesTrend(List<ShopSalesDaily> sales) {
        return sales.stream().map(row -> {
            ShopSalesTrendDTO dto = new ShopSalesTrendDTO();
            dto.setDate(row.getStatDate());
            dto.setSalesAmount(nvl(row.getSalesAmount()));
            dto.setOrderCount(nvl(row.getOrderCount()));
            dto.setBuyerCount(nvl(row.getBuyerCount()));
            return dto;
        }).toList();
    }

    private List<ShopProductRankDTO> buildHotProducts(List<ShopProduct> products, List<ShopProductSalesDaily> productSales) {
        Map<Long, ShopProduct> productMap = products.stream().collect(Collectors.toMap(ShopProduct::getId, Function.identity()));
        return productSales.stream()
                .collect(Collectors.groupingBy(ShopProductSalesDaily::getProductId))
                .entrySet().stream()
                .map(e -> toProductRank(productMap.get(e.getKey()), e.getValue(), null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ShopProductRankDTO::getQuantitySold).reversed())
                .limit(5)
                .toList();
    }

    private List<ShopProductRankDTO> buildSlowProducts(List<ShopProduct> products, List<ShopProductSalesDaily> productSales) {
        Map<Long, ShopProduct> productMap = products.stream().collect(Collectors.toMap(ShopProduct::getId, Function.identity()));
        return productSales.stream()
                .collect(Collectors.groupingBy(ShopProductSalesDaily::getProductId))
                .entrySet().stream()
                .map(e -> toProductRank(productMap.get(e.getKey()), e.getValue(), "库存较高，近 7 日销量偏低"))
                .filter(Objects::nonNull)
                .filter(dto -> dto.getStock() != null && dto.getStock() > 50 && dto.getQuantitySold() <= 15)
                .sorted(Comparator.comparing(ShopProductRankDTO::getQuantitySold))
                .limit(5)
                .toList();
    }

    private ShopProductRankDTO toProductRank(ShopProduct product, List<ShopProductSalesDaily> rows, String reason) {
        if (product == null) return null;
        int sold = rows.stream().mapToInt(row -> nvl(row.getQuantitySold())).sum();
        int orders = rows.stream().mapToInt(row -> nvl(row.getOrderCount())).sum();
        BigDecimal sales = rows.stream().map(row -> nvl(row.getSalesAmount())).reduce(ZERO, BigDecimal::add);
        int stock = rows.stream().max(Comparator.comparing(ShopProductSalesDaily::getStatDate)).map(row -> nvl(row.getStock())).orElse(nvl(product.getStock()));
        BigDecimal firstAvg = avgSold(rows, 7, 4);
        BigDecimal lastAvg = avgSold(rows, 3, 0);
        BigDecimal trendRate = changeRate(lastAvg, firstAvg);

        ShopProductRankDTO dto = new ShopProductRankDTO();
        dto.setProductId(product.getId());
        dto.setProductName(product.getProductName());
        dto.setSalesAmount(sales.setScale(2, RoundingMode.HALF_UP));
        dto.setOrderCount(orders);
        dto.setQuantitySold(sold);
        dto.setStock(stock);
        dto.setTrend(trendRate.compareTo(new BigDecimal("20")) > 0 ? "up" : trendRate.compareTo(new BigDecimal("-20")) < 0 ? "down" : "stable");
        dto.setTrendRate(trendRate);
        dto.setReason(reason);
        return dto;
    }

    private BigDecimal avgSold(List<ShopProductSalesDaily> rows, int days, int skipLatest) {
        List<ShopProductSalesDaily> sorted = rows.stream().sorted(Comparator.comparing(ShopProductSalesDaily::getStatDate).reversed()).toList();
        int sum = sorted.stream().skip(skipLatest).limit(days).mapToInt(row -> nvl(row.getQuantitySold())).sum();
        return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }

    private ShopCustomerProfileDTO buildCustomerProfile(ShopCustomerSummary customer) {
        ShopCustomerProfileDTO dto = new ShopCustomerProfileDTO();
        dto.setNewCustomerCount(customer == null ? 0 : nvl(customer.getNewCustomerCount()));
        dto.setRepeatCustomerCount(customer == null ? 0 : nvl(customer.getRepeatCustomerCount()));
        dto.setHighValueCustomerCount(customer == null ? 0 : nvl(customer.getHighValueCustomerCount()));
        dto.setAvgCustomerValue(customer == null ? ZERO : nvl(customer.getAvgCustomerValue()));
        dto.setFemaleRatio(customer == null ? ZERO : nvl(customer.getFemaleRatio()));
        dto.setMaleRatio(customer == null ? ZERO : nvl(customer.getMaleRatio()));
        dto.setTopRegions(customer == null ? List.of() : parseNameValues(customer.getTopRegions()));
        dto.setAgeDistribution(customer == null ? List.of() : parseNameValues(customer.getAgeDistribution()));
        return dto;
    }

    private List<NameValueDTO> parseNameValues(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ShopReplenishmentSuggestionDTO> buildReplenishment(List<ShopProduct> products, List<ShopProductSalesDaily> productSales) {
        Map<Long, ShopProduct> productMap = products.stream().collect(Collectors.toMap(ShopProduct::getId, Function.identity()));
        return productSales.stream()
                .collect(Collectors.groupingBy(ShopProductSalesDaily::getProductId))
                .entrySet().stream()
                .map(e -> toReplenishment(productMap.get(e.getKey()), e.getValue()))
                .filter(Objects::nonNull)
                .filter(dto -> dto.getSuggestedReplenishment() > 0)
                .sorted(Comparator.comparing(ShopReplenishmentSuggestionDTO::getEstimatedDaysLeft))
                .limit(8)
                .toList();
    }

    private ShopReplenishmentSuggestionDTO toReplenishment(ShopProduct product, List<ShopProductSalesDaily> rows) {
        if (product == null) return null;
        int sold = rows.stream().mapToInt(row -> nvl(row.getQuantitySold())).sum();
        BigDecimal avg = BigDecimal.valueOf(sold).divide(BigDecimal.valueOf(7), 2, RoundingMode.HALF_UP);
        int stock = rows.stream().max(Comparator.comparing(ShopProductSalesDaily::getStatDate)).map(row -> nvl(row.getStock())).orElse(nvl(product.getStock()));
        BigDecimal daysLeft = avg.compareTo(ZERO) == 0 ? new BigDecimal("999.00") : BigDecimal.valueOf(stock).divide(avg, 2, RoundingMode.HALF_UP);
        int suggested = avg.multiply(BigDecimal.valueOf(14)).subtract(BigDecimal.valueOf(stock)).setScale(0, RoundingMode.CEILING).max(BigDecimal.ZERO).intValue();

        ShopReplenishmentSuggestionDTO dto = new ShopReplenishmentSuggestionDTO();
        dto.setProductId(product.getId());
        dto.setProductName(product.getProductName());
        dto.setStock(stock);
        dto.setAvgDailySales(avg);
        dto.setEstimatedDaysLeft(daysLeft);
        dto.setSuggestedReplenishment(suggested);
        dto.setPriority(daysLeft.compareTo(new BigDecimal("3")) <= 0 ? "high" : daysLeft.compareTo(new BigDecimal("7")) <= 0 ? "medium" : "low");
        dto.setReason("近 7 日日均销量 " + avg + " 件，预计可售 " + daysLeft + " 天");
        return dto;
    }

    private List<ShopActivitySuggestionDTO> buildActivitySuggestions(ShopTodayMetricsDTO today, List<ShopProductRankDTO> hotProducts, List<ShopProductRankDTO> slowProducts, List<ShopReplenishmentSuggestionDTO> replenishment) {
        List<ShopActivitySuggestionDTO> suggestions = new ArrayList<>();
        if (!hotProducts.isEmpty()) {
            ShopProductRankDTO hot = hotProducts.get(0);
            suggestions.add(activity("promotion", "围绕爆款商品做搭配购", "「" + hot.getProductName() + "」近 7 日表现突出，可搭配低动销商品提升连带率。", "medium"));
        }
        if (replenishment.stream().anyMatch(item -> "high".equals(item.getPriority()))) {
            suggestions.add(activity("stock", "先补货再放量投放", "部分商品预计 3 天内售罄，建议先补货再加大活动曝光。", "high"));
        }
        if (!slowProducts.isEmpty()) {
            suggestions.add(activity("clearance", "设置滞销商品限时折扣", "滞销商品库存偏高，可通过清仓专区、买赠或组合销售降低库存压力。", "medium"));
        }
        if (today.getAverageOrderValue().compareTo(new BigDecimal("100")) < 0 && today.getOrderCount() > 0) {
            suggestions.add(activity("aov", "提高满减门槛和套装占比", "当前客单价偏低，建议用满减、加价购和套装提升单均收入。", "low"));
        }
        if (today.getRepeatCustomerCount() > 20) {
            suggestions.add(activity("customer", "给老客发放专享券", "复购用户活跃，可通过会员券和老客专享活动提高复购频次。", "low"));
        }
        return suggestions;
    }

    private ShopActivitySuggestionDTO activity(String type, String title, String description, String priority) {
        ShopActivitySuggestionDTO dto = new ShopActivitySuggestionDTO();
        dto.setType(type);
        dto.setTitle(title);
        dto.setDescription(description);
        dto.setPriority(priority);
        return dto;
    }

    private String buildReportContent(String title, ShopOverviewDTO overview) {
        ShopTodayMetricsDTO today = overview.getToday();
        String hot = overview.getHotProducts().isEmpty() ? "暂无明显爆款" : overview.getHotProducts().get(0).getProductName();
        String slow = overview.getSlowProducts().isEmpty() ? "暂无明显滞销商品" : overview.getSlowProducts().get(0).getProductName();
        String replenish = overview.getReplenishmentSuggestions().isEmpty() ? "当前暂无紧急补货项。" : "「" + overview.getReplenishmentSuggestions().get(0).getProductName() + "」建议优先补货 " + overview.getReplenishmentSuggestions().get(0).getSuggestedReplenishment() + " 件。";
        String activity = overview.getActivitySuggestions().isEmpty() ? "保持当前经营节奏，继续观察商品转化。" : overview.getActivitySuggestions().get(0).getDescription();

        return "# " + title + "\n\n"
                + "## 今日经营概览\n"
                + "今日销售额 ¥" + today.getSalesAmount() + "，订单数 " + today.getOrderCount() + " 单，客单价 ¥" + today.getAverageOrderValue() + "。\n\n"
                + "## 商品表现\n"
                + "爆款商品：" + hot + "。滞销商品：" + slow + "。\n\n"
                + "## 客户表现\n"
                + "复购用户 " + overview.getCustomers().getRepeatCustomerCount() + " 人，高价值客户 " + overview.getCustomers().getHighValueCustomerCount() + " 人。\n\n"
                + "## 补货建议\n"
                + replenish + "\n\n"
                + "## 活动建议\n"
                + activity + "\n";
    }

    private String buildReportSummary(ShopOverviewDTO overview) {
        return "今日销售额 ¥" + overview.getToday().getSalesAmount()
                + "，订单数 " + overview.getToday().getOrderCount()
                + " 单，补货建议 " + overview.getReplenishmentSuggestions().size()
                + " 条。";
    }

    private boolean hasHighRisk(ShopOverviewDTO overview) {
        return overview.getReplenishmentSuggestions().stream().anyMatch(item -> "high".equals(item.getPriority())) || !overview.getSlowProducts().isEmpty();
    }

    private ShopAiReportDTO toAiReportDTO(ShopAiReport report) {
        ShopAiReportDTO dto = new ShopAiReportDTO();
        dto.setId(report.getId());
        dto.setReportDate(report.getReportDate());
        dto.setTitle(report.getTitle());
        dto.setSummary(report.getSummary());
        dto.setContent(report.getContent());
        dto.setRiskLevel(report.getRiskLevel());
        dto.setGeneratedBy(report.getGeneratedBy());
        dto.setCreatedAt(report.getCreatedAt());
        return dto;
    }

    private BigDecimal changeRate(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return current.compareTo(BigDecimal.ZERO) == 0 ? ZERO : new BigDecimal("100.00");
        }
        return current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal nvl(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    private int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
