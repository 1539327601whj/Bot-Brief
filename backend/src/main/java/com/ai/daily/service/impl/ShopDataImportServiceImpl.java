package com.ai.daily.service.impl;

import com.ai.daily.dto.ShopImportConfirmDTO;
import com.ai.daily.dto.ShopImportErrorDTO;
import com.ai.daily.dto.ShopImportPreviewDTO;
import com.ai.daily.entity.ShopProduct;
import com.ai.daily.entity.ShopProductSalesDaily;
import com.ai.daily.entity.ShopSalesDaily;
import com.ai.daily.entity.ShopStore;
import com.ai.daily.mapper.ShopProductMapper;
import com.ai.daily.mapper.ShopProductSalesDailyMapper;
import com.ai.daily.mapper.ShopSalesDailyMapper;
import com.ai.daily.service.ShopDataImportService;
import com.ai.daily.service.ShopStoreService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
public class ShopDataImportServiceImpl implements ShopDataImportService {

    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final int MAX_ROWS = 10_000;
    private static final int PREVIEW_ROWS = 20;
    private static final Map<String, List<String>> HEADERS = Map.of(
            "PRODUCT", List.of("external_product_id", "product_name", "category", "price", "stock"),
            "STORE_DAILY", List.of("stat_date", "sales_amount", "order_count", "buyer_count", "refund_amount"),
            "PRODUCT_DAILY", List.of("external_product_id", "stat_date", "sales_amount", "order_count", "quantity_sold", "stock")
    );

    private final ShopStoreService shopStoreService;
    private final ShopProductMapper productMapper;
    private final ShopSalesDailyMapper salesDailyMapper;
    private final ShopProductSalesDailyMapper productSalesDailyMapper;

    public ShopDataImportServiceImpl(ShopStoreService shopStoreService,
                                     ShopProductMapper productMapper,
                                     ShopSalesDailyMapper salesDailyMapper,
                                     ShopProductSalesDailyMapper productSalesDailyMapper) {
        this.shopStoreService = shopStoreService;
        this.productMapper = productMapper;
        this.salesDailyMapper = salesDailyMapper;
        this.productSalesDailyMapper = productSalesDailyMapper;
    }

    @Override
    public String template(String type) {
        String normalized = normalizeType(type);
        return "﻿" + String.join(",", HEADERS.get(normalized)) + "\n" + switch (normalized) {
            case "PRODUCT" -> "SKU-001,示例商品,示例分类,99.00,100\n";
            case "STORE_DAILY" -> "2026-07-20,12888.50,136,120,388.00\n";
            case "PRODUCT_DAILY" -> "SKU-001,2026-07-20,1980.00,18,20,80\n";
            default -> throw new IllegalArgumentException("不支持的导入类型");
        };
    }

    @Override
    public ShopImportPreviewDTO preview(Long userId, Long storeId, String type, MultipartFile file) {
        ShopStore store = requireStore(userId, storeId);
        byte[] bytes = readFile(file);
        ParsedCsv parsed = parse(userId, store.getId(), normalizeType(type), bytes);
        return toPreview(parsed, hash(bytes));
    }

    @Override
    @Transactional
    public ShopImportConfirmDTO confirm(Long userId, Long storeId, String type, String fileHash, MultipartFile file) {
        ShopStore store = requireStore(userId, storeId);
        String normalized = normalizeType(type);
        byte[] bytes = readFile(file);
        String actualHash = hash(bytes);
        if (fileHash == null || !actualHash.equalsIgnoreCase(fileHash)) {
            throw new IllegalArgumentException("文件与预览时不一致，请重新预览");
        }
        ParsedCsv parsed = parse(userId, store.getId(), normalized, bytes);
        if (!parsed.errors().isEmpty()) {
            throw new IllegalArgumentException("文件仍有校验错误，请修正后重新预览");
        }
        for (Map<String, String> row : parsed.rows()) {
            switch (normalized) {
                case "PRODUCT" -> upsertProduct(userId, store, row);
                case "STORE_DAILY" -> upsertStoreDaily(userId, store.getId(), row);
                case "PRODUCT_DAILY" -> upsertProductDaily(userId, store.getId(), row);
                default -> throw new IllegalArgumentException("不支持的导入类型");
            }
        }
        return new ShopImportConfirmDTO(normalized, parsed.rows().size());
    }

    private ParsedCsv parse(Long userId, Long storeId, String type, byte[] bytes) {
        List<ShopImportErrorDTO> errors = new ArrayList<>();
        List<Map<String, String>> rows = new ArrayList<>();
        String content = decodeUtf8(bytes);
        if (content.startsWith("﻿")) content = content.substring(1);

        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setIgnoreEmptyLines(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {
            List<String> expectedHeaders = HEADERS.get(type);
            List<String> actualHeaders = new ArrayList<>(parser.getHeaderMap().keySet());
            if (!actualHeaders.equals(expectedHeaders)) {
                errors.add(new ShopImportErrorDTO(1L, "header", "表头必须为：" + String.join(",", expectedHeaders)));
                return new ParsedCsv(type, rows, errors);
            }

            Set<String> seenKeys = new HashSet<>();
            for (CSVRecord record : parser) {
                if (record.getRecordNumber() > MAX_ROWS) {
                    errors.add(new ShopImportErrorDTO(record.getRecordNumber() + 1, "file", "最多允许 " + MAX_ROWS + " 行数据"));
                    break;
                }
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : expectedHeaders) row.put(header, record.get(header).trim());
                List<ShopImportErrorDTO> rowErrors = validateRow(userId, storeId, type, record.getRecordNumber() + 1, row, seenKeys);
                if (rowErrors.isEmpty()) rows.add(row); else errors.addAll(rowErrors);
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new IllegalArgumentException("CSV 解析失败：" + e.getMessage());
        }
        if (rows.isEmpty() && errors.isEmpty()) {
            errors.add(new ShopImportErrorDTO(2L, "file", "CSV 没有数据行"));
        }
        return new ParsedCsv(type, rows, errors);
    }

    private List<ShopImportErrorDTO> validateRow(Long userId, Long storeId, String type, long rowNumber,
                                                  Map<String, String> row, Set<String> seenKeys) {
        List<ShopImportErrorDTO> errors = new ArrayList<>();
        switch (type) {
            case "PRODUCT" -> {
                required(rowNumber, row, "external_product_id", errors);
                required(rowNumber, row, "product_name", errors);
                decimal(rowNumber, row, "price", 10, errors);
                integer(rowNumber, row, "stock", errors);
                if (errors.isEmpty()) duplicate(rowNumber, row.get("external_product_id"), "external_product_id", seenKeys, errors);
            }
            case "STORE_DAILY" -> {
                date(rowNumber, row, "stat_date", errors);
                decimal(rowNumber, row, "sales_amount", 12, errors);
                integer(rowNumber, row, "order_count", errors);
                integer(rowNumber, row, "buyer_count", errors);
                decimal(rowNumber, row, "refund_amount", 12, errors);
                if (errors.isEmpty()) duplicate(rowNumber, row.get("stat_date"), "stat_date", seenKeys, errors);
            }
            case "PRODUCT_DAILY" -> {
                required(rowNumber, row, "external_product_id", errors);
                date(rowNumber, row, "stat_date", errors);
                decimal(rowNumber, row, "sales_amount", 12, errors);
                integer(rowNumber, row, "order_count", errors);
                integer(rowNumber, row, "quantity_sold", errors);
                integer(rowNumber, row, "stock", errors);
                String externalId = row.get("external_product_id");
                if (!externalId.isBlank() && findProduct(userId, storeId, externalId) == null) {
                    errors.add(new ShopImportErrorDTO(rowNumber, "external_product_id", "未找到该商品，请先导入商品基础数据"));
                }
                if (errors.isEmpty()) duplicate(rowNumber, externalId + "|" + row.get("stat_date"), "external_product_id,stat_date", seenKeys, errors);
            }
            default -> throw new IllegalArgumentException("不支持的导入类型");
        }
        return errors;
    }

    private void upsertProduct(Long userId, ShopStore store, Map<String, String> row) {
        ShopProduct product = findProduct(userId, store.getId(), row.get("external_product_id"));
        if (product == null) product = new ShopProduct();
        product.setUserId(userId);
        product.setStoreId(store.getId());
        product.setPlatform(store.getPlatform());
        product.setExternalProductId(row.get("external_product_id"));
        product.setProductName(row.get("product_name"));
        product.setCategory(blankToNull(row.get("category")));
        product.setPrice(new BigDecimal(row.get("price")));
        product.setStock(Integer.valueOf(row.get("stock")));
        product.setStatus("active");
        if (product.getId() == null) productMapper.insert(product); else productMapper.updateById(product);
    }

    private void upsertStoreDaily(Long userId, Long storeId, Map<String, String> row) {
        LocalDate statDate = LocalDate.parse(row.get("stat_date"));
        ShopSalesDaily daily = salesDailyMapper.selectOne(new LambdaQueryWrapper<ShopSalesDaily>()
                .eq(ShopSalesDaily::getUserId, userId)
                .eq(ShopSalesDaily::getStoreId, storeId)
                .eq(ShopSalesDaily::getStatDate, statDate)
                .last("LIMIT 1"));
        if (daily == null) daily = new ShopSalesDaily();
        daily.setUserId(userId);
        daily.setStoreId(storeId);
        daily.setStatDate(statDate);
        daily.setSalesAmount(new BigDecimal(row.get("sales_amount")));
        daily.setOrderCount(Integer.valueOf(row.get("order_count")));
        daily.setBuyerCount(Integer.valueOf(row.get("buyer_count")));
        daily.setRefundAmount(new BigDecimal(row.get("refund_amount")));
        if (daily.getId() == null) salesDailyMapper.insert(daily); else salesDailyMapper.updateById(daily);
    }

    private void upsertProductDaily(Long userId, Long storeId, Map<String, String> row) {
        ShopProduct product = findProduct(userId, storeId, row.get("external_product_id"));
        if (product == null) throw new IllegalArgumentException("商品不存在，请重新预览");
        LocalDate statDate = LocalDate.parse(row.get("stat_date"));
        ShopProductSalesDaily daily = productSalesDailyMapper.selectOne(new LambdaQueryWrapper<ShopProductSalesDaily>()
                .eq(ShopProductSalesDaily::getUserId, userId)
                .eq(ShopProductSalesDaily::getStoreId, storeId)
                .eq(ShopProductSalesDaily::getProductId, product.getId())
                .eq(ShopProductSalesDaily::getStatDate, statDate)
                .last("LIMIT 1"));
        if (daily == null) daily = new ShopProductSalesDaily();
        daily.setUserId(userId);
        daily.setStoreId(storeId);
        daily.setProductId(product.getId());
        daily.setStatDate(statDate);
        daily.setSalesAmount(new BigDecimal(row.get("sales_amount")));
        daily.setOrderCount(Integer.valueOf(row.get("order_count")));
        daily.setQuantitySold(Integer.valueOf(row.get("quantity_sold")));
        daily.setStock(Integer.valueOf(row.get("stock")));
        if (daily.getId() == null) productSalesDailyMapper.insert(daily); else productSalesDailyMapper.updateById(daily);
        ShopProductSalesDaily latest = productSalesDailyMapper.selectOne(new LambdaQueryWrapper<ShopProductSalesDaily>()
                .eq(ShopProductSalesDaily::getUserId, userId)
                .eq(ShopProductSalesDaily::getStoreId, storeId)
                .eq(ShopProductSalesDaily::getProductId, product.getId())
                .orderByDesc(ShopProductSalesDaily::getStatDate)
                .last("LIMIT 1"));
        if (latest != null) {
            product.setStock(latest.getStock());
            productMapper.updateById(product);
        }
    }

    private ShopProduct findProduct(Long userId, Long storeId, String externalId) {
        return productMapper.selectOne(new LambdaQueryWrapper<ShopProduct>()
                .eq(ShopProduct::getUserId, userId)
                .eq(ShopProduct::getStoreId, storeId)
                .eq(ShopProduct::getExternalProductId, externalId)
                .last("LIMIT 1"));
    }

    private ShopImportPreviewDTO toPreview(ParsedCsv parsed, String fileHash) {
        ShopImportPreviewDTO dto = new ShopImportPreviewDTO();
        dto.setType(parsed.type());
        dto.setFileHash(fileHash);
        dto.setTotalRows(parsed.rows().size() + Math.toIntExact(parsed.errors().stream().map(ShopImportErrorDTO::getRow).distinct().count()));
        dto.setValidRows(parsed.rows().size());
        dto.setErrors(parsed.errors());
        dto.setPreviewRows(parsed.rows().stream().limit(PREVIEW_ROWS).toList());
        return dto;
    }

    private ShopStore requireStore(Long userId, Long storeId) {
        ShopStore store = shopStoreService.getForUser(userId, storeId);
        if (store == null) throw new IllegalArgumentException("店铺不存在");
        return store;
    }

    private byte[] readFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择 CSV 文件");
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            throw new IllegalArgumentException("仅支持 .csv 文件");
        }
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("CSV 文件不能超过 5MB");
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 CSV 文件失败");
        }
    }

    private String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("CSV 必须使用 UTF-8 编码");
        }
    }

    private String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("计算文件哈希失败", e);
        }
    }

    private String normalizeType(String type) {
        String normalized = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!HEADERS.containsKey(normalized)) throw new IllegalArgumentException("不支持的导入类型");
        return normalized;
    }

    private void required(long row, Map<String, String> values, String field, List<ShopImportErrorDTO> errors) {
        if (values.get(field) == null || values.get(field).isBlank()) errors.add(new ShopImportErrorDTO(row, field, "不能为空"));
    }

    private void decimal(long row, Map<String, String> values, String field, int integerDigits, List<ShopImportErrorDTO> errors) {
        try {
            BigDecimal value = new BigDecimal(values.get(field));
            int actualIntegerDigits = Math.max(0, value.precision() - value.scale());
            if (value.compareTo(BigDecimal.ZERO) < 0 || value.scale() > 2 || actualIntegerDigits > integerDigits) {
                throw new NumberFormatException();
            }
        } catch (Exception e) {
            errors.add(new ShopImportErrorDTO(row, field, "必须是非负数字，最多 " + integerDigits + " 位整数和 2 位小数"));
        }
    }

    private void integer(long row, Map<String, String> values, String field, List<ShopImportErrorDTO> errors) {
        try {
            int value = Integer.parseInt(values.get(field));
            if (value < 0) throw new NumberFormatException();
        } catch (Exception e) {
            errors.add(new ShopImportErrorDTO(row, field, "必须是非负整数"));
        }
    }

    private void date(long row, Map<String, String> values, String field, List<ShopImportErrorDTO> errors) {
        try {
            LocalDate value = LocalDate.parse(values.get(field));
            if (value.isAfter(LocalDate.now())) {
                errors.add(new ShopImportErrorDTO(row, field, "不能导入未来日期"));
            }
        } catch (DateTimeParseException | NullPointerException e) {
            errors.add(new ShopImportErrorDTO(row, field, "日期格式必须是 YYYY-MM-DD"));
        }
    }

    private void duplicate(long row, String key, String field, Set<String> seenKeys, List<ShopImportErrorDTO> errors) {
        if (key != null && !key.isBlank() && !seenKeys.add(key)) {
            errors.add(new ShopImportErrorDTO(row, field, "文件内存在重复记录"));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record ParsedCsv(String type, List<Map<String, String>> rows, List<ShopImportErrorDTO> errors) {
    }
}
