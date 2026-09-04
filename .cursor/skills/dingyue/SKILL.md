---
name: dingyue
description: >-
  Continuously builds BriefMind subscription, channel binding, and on-time
  push (兴趣订阅、时段、渠道、准时投递、通知记录) until the module is solid.
  Plans one item, self-heals, skips when possible, commits, pushes main,
  verifies the live site, and loops. Surfaces leftover human work on the
  subscription page. Use when the user says dingyue, /dingyue, 订阅,
  订阅推送, or 准时推送.
disable-model-invocation: true
---

# dingyue

每次被调用时：只做 **订阅 / 渠道绑定 / 准时推送** 这一块。自己看现状、订计划、排优先级，然后一项一项做。做完先自测；没问题就写清楚改了什么，提交并推送到远程 `main`（会自动部署）。部署后再打开网站验证。没问题做下一项；有问题就对着现象和日志改，再测、再推，直到这一项彻底好。

不是非要人工才能解决的问题，优先自己解决。能跳过且不挡后面的，先跳过并记下。自己完全解决不了的，写入 state「待你处理」，并尽量出现在订阅页的「需要你处理」。一直循环，直到这个模块稳了、计划里没有未完成的高优先级项，再停。

状态记在 [dingyue-state.md](../../dingyue-state.md)（没有就创建）。模块细节见 [reference.md](reference.md)。

店铺分析用 **dianpu**。短视频用 **shipin**。公共日报版式 / 行情算法用 **youhua**。不要在本 skill 里顺手改那些。

## 每次启动

1. 读 `dingyue-state.md`（有则接着做，不要推倒重来）。
2. 看现状：`git status`、`git log -8`、`git diff`、最近 Actions、订阅/推送相关代码、线上 `/subscription` 与通知记录。
3. 对照 reference 列出缺口：状态刷新、渠道失败终点、绑定继承、过近时刻、星期默认、ETF 只能傍晚、需要你处理、通知聚合、补扫过宽、测试空洞。
4. 写出或更新计划，按 P0 → P1 → P2。同一时间只执行 **一项**。
5. 把计划写进 `dingyue-state.md`，并告诉用户这一轮做哪一项。

不要同时开多个大改。不要为了「完美」去改店铺或短视频。

## 产品边界

- 生成可以提前，网页展示和渠道推送必须按用户订阅时刻（北京时间）。
- 普通用户只看自己的个人简报和自己的投递问题。公共 AI 早晚报 / ETF 原文仍只给管理员和 Demo。
- **纳指标普沪深300ETF**（及别名 etf / 市场观察）只能订傍晚窗口（`w18_24`，默认 18:00）。不要再让早晨槽去复用晚报原文而不改规则。
- 「需要你处理」做在 **订阅管理**：当前用户看自己的渠道/订阅问题；管理员额外看全站项（生成器心跳等）。不要做成单独的 Admin 运维页。
- 没绑渠道 = 仅网页，不要偷偷改成推该用户全部渠道。
- 改隔离策略必须用户明确要求。

## 做一项

1. 只改完成这一项所必需的文件（见 reference 模块边界）。
2. 自测（能跑的都跑）：
   - 后端：`mvn -f backend/pom.xml "-Dtest=相关测试" test`（PowerShell 必须给 `-Dtest` 加引号）
   - 前端：`npm --prefix frontend test`
   - 与推送/生成相关：补跑对应 Python 单测
   - 与 UI 有关：用浏览器按真实用户路径走一遍——订阅管理勾选/改时刻/绑渠道/保存、看今日进度、通知记录、首页相关栏。不要只截一张图
3. 自测不过：先修，再测。不要带着红测试提交。
4. 自测通过：写清改了什么，再提交并推送。
5. 等 **Deploy on server** 变绿。失败就看 Actions / 日志，修、测、再推。
6. 打开 `http://124.222.194.103/subscription`（以及这次改到的通知、首页），验证这次改动。
7. 线上有问题：对着现象改 → 自测 → 提交推送 → 再部署 → 再看。未解决前不要开始下一项。
8. 线上没问题：在 state 里勾掉这项，开始下一项。

## 提交和推送

用户调用 dingyue 即授权：**每完成一轮可发布的修复，提交并推送到 `origin/main`。**

仍遵守：

- 不改 git config；不用 `-i`；不 `--no-verify`；不 force push `main`
- 不提交 `.env`、密钥、渠道 webhook 明文、`credentials.json`
- 先 `git status`、`git diff`、`git log -8`，再按仓库近期文风写说明
- 说明写 **为什么**（1–2 句），并列出改了什么
- PowerShell：

```powershell
git add <相关文件>
git commit -m "简短说明。`n`n- 改动1`n- 改动2"
git status
git push origin HEAD:main
```

推送后看 **Deploy on server** 和（若动了 backend/automation）**市场数据链路测试**。只改了 `.cursor/**` 不会触发部署时，跳过等部署，仍做能做的本地验证。

## 卡住时

先自己处理；还是解决不了再分类：

- **自己修**：测试红、部署编译、状态不刷新、重试上限、文案、缺列、幂等键、ETF 傍晚校验。修完再走提交循环。
- **可跳过**：没浏览器只能测接口、某个渠道文档不全但不挡网页投递、P2 美观。写入 state「已跳过 / 未验证」，继续下一项。
- **必须你来**：企业微信/邮箱密钥、self-hosted runner 离线、产品要不要改隔离。写入 state「待你处理」，告诉用户缺什么，并在订阅页「需要你处理」能展示的就展示。
- 不挡后面任务：先做下一项；用户说那个问题好了再回来。
- 挡后面任务：停在这一项，等用户。

不要假装线上已验证。没有浏览器或 Actions 权限时，写明没验证什么。

## 何时停

同时满足再停，并写「dingyue 本轮结束」：

- state 里没有未完成的 P0 / P1
- 本轮改动的测试和（如有部署）线上抽查已通过
- 没有未告知用户的阻塞项

P2 可留到用户再次调用 dingyue。用户说停就立即停。

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
