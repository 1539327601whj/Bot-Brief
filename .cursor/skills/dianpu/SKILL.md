---
name: dianpu
description: >-
  Continuously builds BriefMind shop analytics (店铺分析: stores, CSV import,
  sales/product/customer/inventory, rule-based daily report) until the module
  is solid. Plans one item, implements, self-tests, commits, pushes main,
  verifies the live site, and loops. Use when the user says dianpu, /dianpu,
  店铺, or 店铺分析.
disable-model-invocation: true
---

# dianpu

每次被调用时：只做 **店铺分析** 这一块。自己看现状、订计划、排优先级，然后一项一项做。做完先自测；没问题就写清楚改了什么，提交并推送到远程 `main`（会自动部署）。部署后再打开网站验证。没问题做下一项；有问题就对着现象和日志改，再测、再推，直到这一项彻底好。不是非要人工才能解决的问题，优先自己解决。自己完全解决不了的，告诉用户。一直循环，直到这个模块稳了、计划里没有未完成的高优先级项，再停。

状态记在 [dianpu-state.md](../../dianpu-state.md)（没有就创建）。模块细节见 [reference.md](reference.md)。

公共日报版式 / ETF 行情用 **youhua**。订阅推送用 **dingyue**。短视频 / 内容增长用 **shipin**。不要在本 skill 里顺手改那些。

## 每次启动

1. 读 `dianpu-state.md`（有则接着做，不要推倒重来）。
2. 看现状：`git status`、`git log -8`、`git diff`、最近 Actions、店铺相关代码、线上 `/shop-analytics`。
3. 对照 reference 列出缺口：店铺创建/切换、CSV 预览确认、概览数字、空状态、隔离、缺表缺列、测试空洞、经营日报、补货/活动建议。
4. 写出或更新计划，按 P0 → P1 → P2。同一时间只执行 **一项**。
5. 把计划写进 `dianpu-state.md`，并告诉用户这一轮做哪一项。

不要同时开多个大改。不要为了「完美」去改日报或短视频。

## 产品边界

当前能力是 **用户自己的店 + 自己的 CSV / 演示数据**，按 `user_id` + `store_id` 算出销售、商品、客户、库存建议，并可生成规则版经营日报。

- 没有淘宝 / 抖店 / 拼多多官方同步。不要一上来做未授权抓取、Cookie 登录、假装已经 OAuth。
- 经营日报默认是规则生成（`generated_by=rule`），不是 DeepSeek。不要偷偷改成大模型，除非用户本轮明确说。
- 今天没有经营汇总时，展示最近数据日，并标明不是今天。不要把历史数据冒充今日。
- Demo 只读本地夹具，不要拿 Demo JWT 去写生产店铺表。
- 改隔离必须用户明确要求。

## 做一项

1. 只改完成这一项所必需的文件（见 reference 模块边界）。
2. 自测（能跑的都跑）：
   - 后端：`mvn -f backend/pom.xml "-Dtest=相关测试" test`（PowerShell 必须给 `-Dtest` 加引号）
   - 前端：`npm --prefix frontend test`
   - 与 UI 有关：用浏览器按真实用户路径走一遍——进店铺分析、切店、导入或生成演示数据、看概览/趋势/补货/日报。不要只截一张图
3. 自测不过：先修，再测。不要带着红测试提交。
4. 自测通过：写清改了什么，再提交并推送。
5. 等 **Deploy on server** 变绿。失败就看 Actions / 日志，修、测、再推。
6. 打开 `http://124.222.194.103/shop-analytics`（以及这次改到的相邻页），验证这次改动。
7. 线上有问题：对着现象改 → 自测 → 提交推送 → 再部署 → 再看。未解决前不要开始下一项。
8. 线上没问题：在 state 里勾掉这项，开始下一项。

## 提交和推送

用户调用 dianpu 即授权：**每完成一轮可发布的修复，提交并推送到 `origin/main`。**

仍遵守：

- 不改 git config；不用 `-i`；不 `--no-verify`；不 force push `main`
- 不提交 `.env`、密钥、用户店铺 CSV 原文件
- 先 `git status`、`git diff`、`git log -8`，再按仓库近期文风写说明
- 说明写 **为什么**（1–2 句），并列出改了什么
- PowerShell：

```powershell
git add <相关文件>
git commit -m "简短说明。`n`n- 改动1`n- 改动2"
git status
git push origin HEAD:main
```

推送后看 **Deploy on server**。只改了 `.cursor/**` 不会触发部署时，跳过等部署，仍做能做的本地验证。

## 卡住时

- 能自己修的（缺表/缺列、校验、隔离、导入预览、空状态、测试）：自己修完再走提交循环。
- 完全做不了的（要平台开放接口、用户店铺后台密码、要不要上大模型日报）：写入 state「待你处理」，告诉用户缺什么。
- 不挡后面任务：先做下一项；用户说那个问题好了再回来。
- 挡后面任务：停在这一项，等用户。

不要假装线上已验证。没有浏览器或 Actions 权限时，写明没验证什么。

## 何时停

同时满足再停，并写「dianpu 本轮结束」：

- state 里没有未完成的 P0 / P1
- 本轮改动的测试和（如有部署）线上抽查已通过
- 没有未告知用户的阻塞项

P2 可留到用户再次调用 dianpu。用户说停就立即停。

## 回复格式

```markdown
## 本轮
- 做了：...
- 验证：本地 / Actions / 线上
- 提交：<hash> <说明>

## 下一步
- ...

## 需要你处理（如有）
- ...
```
