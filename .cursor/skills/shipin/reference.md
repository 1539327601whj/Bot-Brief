# shipin 模块参考（内容增长 / 短视频账号绑定）

## 入口

- 真正能用的页：`/content-growth`（侧栏「内容增长」）
- 营销壳：`/creator-tools`（侧栏「短视频分析」，带「即将」）。功能应做进 ContentGrowth，不要在 CreatorTools 另起一套数据
- 线上：`http://124.222.194.103/content-growth`
- 套餐文案在 `Pricing.tsx`（创作者版「绑定 1 个短视频账号」），支付未开放，不要当已实现能力

## 绑定现在是什么

用户手工登记账号：平台、名称、主页 URL、粉丝数、定位。后端 `createAccount` 固定 `bindStatus=manual`。作品手工录入或 CSV。竞品只是备忘名单。

没有 OAuth、没有 Cookie、没有平台 API 同步。`bind_status` 列在，前端基本不展示。Demo 夹具里写成 `BOUND`，和后端不一致。

平台白名单（前后端必须一起改）：`douyin` `xiaohongshu` `kuaishou` `bilibili`。

## API

`/api/content-growth`，需 `ACCOUNT_NORMAL`。Demo JWT 调这些接口是 403，前端 Demo 走本地 fixtures。

| 方法 | 路径 | 作用 |
|------|------|------|
| GET/POST | `/accounts` | 列出 / 创建账号 |
| PUT/DELETE | `/accounts/{id}` | 更新（平台不可改）/ 删除并级联作品 |
| GET/POST | `/works` | 分页作品 / 创建 |
| PUT/DELETE | `/works/{id}` | 更新 / 删除 |
| POST | `/works/import` | CSV，最多 500 行 |
| GET | `/overview` | 汇总 |
| POST | `/ai/hot-analysis` 等 | AI 分析（结果写入 `content_growth_analysis`，无读取接口） |
| GET/POST/DELETE | `/competitors` | 竞品，无编辑 |

每条查询按 `user_id` 隔离。改隔离必须用户明确要求。

## 表

`backend/sql/V3__content_growth.sql`（不在 `init.sql` 里，老库可能没建）：

- `content_account`
- `content_work`
- `content_growth_analysis`
- `competitor_account`

生产补表用 `CREATE TABLE IF NOT EXISTS`。不要写 `ADD COLUMN IF NOT EXISTS`（老 MySQL 语法错误）。后端启动由 `ContentGrowthSchemaRepairRunner` 按 information_schema 补建四张表；`init.sql` 新装也会建。

## 模块边界（只改这些，除非跨切认证）

前端：`ContentGrowth.tsx` / `.css`、`CreatorTools.tsx`、`frontend/src/api/contentGrowth.ts`、`frontend/src/utils/csv.ts`、`frontend/src/demo/fixtures.ts`（仅内容增长段落）、`App.tsx`（菜单/路由）、`DemoNotice.tsx`、`BusinessPages.css`（CreatorTools 布局）

后端：`ContentGrowthController`、`ContentGrowthService` / `Impl`、`ContentGrowthDTO`、实体 `ContentAccount` `ContentWork` `CompetitorAccount` `ContentGrowthAnalysis` 及对应 Mapper、`V3__content_growth.sql`

只读共享：`AiClientService`（除非本轮就是改 AI 调用）

不要改：日报 / ETF / 订阅 / 推送渠道「绑定」/ 店铺分析。`savedAccounts.ts` 是登录邮箱书签，无关。

## 自测

```powershell
npm --prefix frontend test
mvn -f backend/pom.xml "-Dtest=ContentGrowthServiceImplTest,ContentGrowthControllerTest" test
```

现在几乎没有本模块单测。补测试算正当 P0/P1，不要无故跳过。

线上抽查：登录普通账号（不要只看 Demo）→ 内容增长 → 添加/编辑/删除账号 → 看列表和空状态。没有账号时注明「未覆盖登录后写入」。

## 不要做

- 提交 `.env`、开放平台 secret、用户 Cookie
- 未授权抓取平台数据
- 把 CreatorTools 做成第二套账号库
- 把公共日报能力塞进这个模块
- 为了「自动同步」先搭一套没有密钥的空 OAuth
