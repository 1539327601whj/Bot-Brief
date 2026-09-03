# dianpu 模块参考（店铺分析）

## 入口

- 页：`/shop-analytics`（侧栏「店铺分析」）
- 线上：`http://124.222.194.103/shop-analytics`
- 套餐文案在 `Pricing.tsx`（商家版「绑定 1 个店铺」），支付未开放，不要当已实现能力

## 现在能做什么

1. 登录后自动有一家默认店；也可再创建店（平台 + 店名）。
2. 导入 UTF-8 CSV（先预览校验，再按文件哈希确认），或一键生成演示数据。店里已有销售日时，演示数据必须带 `overwrite=true`，否则拒绝覆盖。
3. 概览：销售额 / 订单 / 客单价、趋势、商品排行、客户画像、补货建议、活动建议。
4. 经营日报：按规则从概览拼出来，写入 `shop_ai_report`，`generated_by=rule`。

没有官方店铺授权，没有平台 API 拉单。平台标签（淘宝 / 京东 / 抖店 / 视频号小店 / 拼多多 / 快手小店）只是店的分类，不是同步通道。

平台白名单：`taobao` `jd` `douyin` `wechat_shop` `pdd` `kuaishou` `manual`。前后端一起改。

## 导入

三种类型，建议顺序：商品基础 → 店铺每日 → 商品每日。

| type | 表 | 关键列 |
|------|----|--------|
| `PRODUCT` | `shop_product` | `external_product_id, product_name, category, price, stock` |
| `STORE_DAILY` | `shop_sales_daily` | `stat_date, sales_amount, order_count, buyer_count, refund_amount` |
| `PRODUCT_DAILY` | `shop_product_sales_daily` | `external_product_id, stat_date, sales_amount, order_count, quantity_sold, stock` |

约束（`ShopDataImportServiceImpl`）：UTF-8、最大 5MB、最多 10000 行、预览 20 行。确认时文件哈希必须和预览一致。商品靠 `(user_id, store_id, external_product_id)` 幂等覆盖。

今天没有 `shop_sales_daily` 时，概览用最近一个有数据的日期，页面必须提示「不是今天」。

## API

均需登录。`anyRequest` 要求 `ACCOUNT_NORMAL`。Demo JWT 调这些接口是 403，前端 Demo 走 `fixtures.ts`。

| 方法 | 路径 | 作用 |
|------|------|------|
| GET/POST | `/api/shop/stores` | 列出（空则建默认店）/ 创建 |
| GET | `/api/shop/analytics/overview` | 概览，`range` 只认 7 或 30 |
| POST | `/api/shop/analytics/demo-data` | 写入近 30 日演示数据 |
| POST | `/api/shop/analytics/ai-report/generate` | 规则日报，按店+日覆盖 |
| GET | `/api/shop/analytics/ai-report/latest` | 最近一期日报 |
| GET | `/api/shop/analytics/ai-report/history` | 分页历史 |
| GET | `/api/shop/analytics/ai-report/{id}` | 单篇，必须带 `storeId` |
| GET | `/api/shop/import/templates/{type}` | 下载 CSV 模板 |
| POST | `/api/shop/import/preview` | 预览校验 |
| POST | `/api/shop/import/confirm` | 确认入库 |

每条查询按 `user_id` 隔离，店铺必须属于当前用户。改隔离必须用户明确要求。

## 表

`backend/sql/V3__shop_analytics.sql`、`V4__shop_csv_import.sql`（老库可能没建 / 没唯一索引）：

- `shop_store`
- `shop_product`（V4：`uk_shop_product_user_store_external`）
- `shop_sales_daily`
- `shop_product_sales_daily`
- `shop_customer_summary`
- `shop_ai_report`

生产补表用 `CREATE TABLE IF NOT EXISTS`。不要写 `ADD COLUMN IF NOT EXISTS`（老 MySQL 语法错误）。启动时 `ShopAnalyticsSchemaRepairRunner` 按 information_schema 补建六张表，并尽量补上 V4 商品外部 ID 唯一索引。

## 模块边界（只改这些，除非跨切认证）

前端：`ShopAnalytics.tsx` / `.css`、`frontend/src/api/shopAnalytics.ts`、`frontend/src/demo/fixtures.ts`（仅店铺段落）、`App.tsx`（菜单/路由）、`DemoNotice.tsx`、`Pricing.tsx`（仅店铺套餐文案且用户本轮要改）

后端：`ShopAnalyticsController`、`ShopStoreController`、`ShopDataImportController`、`ShopAnalyticsService` / `Impl`、`ShopStoreService` / `Impl`、`ShopDataImportService` / `Impl`、相关 DTO / 实体 / Mapper、`V3__shop_analytics.sql`、`V4__shop_csv_import.sql`

不要改：日报 / ETF / 订阅 / 推送渠道 / 内容增长。

## 自测

```powershell
npm --prefix frontend test
mvn -f backend/pom.xml "-Dtest=ShopAnalyticsServiceImplTest,ShopDataImportServiceImplTest,ShopStoreServiceImplTest" test
```

现在几乎没有本模块单测。补测试算正当 P0/P1，不要无故跳过。导入和「不把历史日冒充今天」是优先要覆盖的。

线上抽查：登录普通账号（不要只看 Demo）→ 店铺分析 → 默认店是否出现 → 生成演示数据或导入 CSV → 看数字、趋势、补货、生成日报。没有账号时注明「未覆盖登录后写入」。

## 不要做

- 提交 `.env`、用户真实经营 CSV
- 未授权去扒淘宝 / 抖店 / 拼多多后台
- 把历史销售日显示成「今日」
- 把经营日报默默改成大模型（除非用户本轮明确说）
- 把公共科技日报或短视频能力塞进这个模块
