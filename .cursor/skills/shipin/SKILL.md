---
name: shipin
description: >-
  Continuously builds BriefMind short-video account binding and content-growth
  (抖音/小红书/快手/B站) until the module is solid. Plans one item, implements,
  self-tests, commits, pushes main, verifies the live site, and loops. Use when
  the user says shipin, /shipin, 短视频, 内容增长, or 绑定账号.
disable-model-invocation: true
---

# shipin

每次被调用时：只做 **短视频平台账号绑定 / 内容增长** 这一块。自己看现状、订计划、排优先级，然后一项一项做。做完先自测；没问题就写清楚改了什么，提交并推送到远程 `main`（会自动部署）。部署后再打开网站验证。没问题做下一项；有问题就对着现象和日志改，再测、再推，直到这一项彻底好。不是非要人工才能解决的问题，优先自己解决。自己完全解决不了的，告诉用户。一直循环，直到这个模块稳了、计划里没有未完成的高优先级项，再停。

状态记在 [shipin-state.md](../../shipin-state.md)（没有就创建）。模块细节见 [reference.md](reference.md)。

日报、ETF、订阅推送不归本 skill。那些用 youhua。

## 每次启动

1. 读 `shipin-state.md`（有则接着做，不要推倒重来）。
2. 看现状：`git status`、`git log -8`、`git diff`、最近 Actions、`ContentGrowth` / 账号绑定相关代码、线上 `/content-growth`。
3. 对照 reference 列出缺口：绑定流程、隔离、校验、测试、空状态、表结构、和「短视频分析」入口是否对得上。
4. 写出或更新计划，按 P0 → P1 → P2。同一时间只执行 **一项**。
5. 把计划写进 `shipin-state.md`，并告诉用户这一轮做哪一项。

不要同时开多个大改。不要为了「完美」去改日报或店铺。

## 产品边界（先做实，再谈自动同步）

当前「绑定」= 用户自己登记平台账号（平台、名称、主页链接、粉丝、定位），`bind_status=manual`，作品靠手工或 CSV。这是第一版要做稳的主路径。

不要一上来就做未授权抓取、Cookie 登录、非官方爬虫。没有用户提供的开放平台密钥时，不要假装已经 OAuth。自动同步、竞品爬取只有在用户明确给了官方能力或拍板方案后才做。

默认平台：`douyin` `xiaohongshu` `kuaishou` `bilibili`。不要擅自加视频号，除非用户本轮明确说。

## 做一项

1. 只改完成这一项所必需的文件（见 reference 模块边界）。
2. 自测（能跑的都跑）：
   - 后端：`mvn -f backend/pom.xml "-Dtest=相关测试" test`（PowerShell 必须给 `-Dtest` 加引号）
   - 前端：`npm --prefix frontend test`
   - 与 UI 有关：用浏览器按真实用户路径走一遍绑定/编辑/删除/空状态，不要只截一张图
3. 自测不过：先修，再测。不要带着红测试提交。
4. 自测通过：写清改了什么，再提交并推送。
5. 等 **Deploy on server** 变绿。失败就看 Actions / 日志，修、测、再推。
6. 打开 `http://124.222.194.103/content-growth`（以及这次改到的相邻页），验证这次改动。
7. 线上有问题：对着现象改 → 自测 → 提交推送 → 再部署 → 再看。未解决前不要开始下一项。
8. 线上没问题：在 state 里勾掉这项，开始下一项。

## 提交和推送

用户调用 shipin 即授权：**每完成一轮可发布的修复，提交并推送到 `origin/main`。**

仍遵守：

- 不改 git config；不用 `-i`；不 `--no-verify`；不 force push `main`
- 不提交 `.env`、密钥、平台 cookie、开放平台 secret
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

- 能自己修的（缺表/缺列、校验、隔离、测试、空状态）：自己修完再走提交循环。
- 完全做不了的（要开放平台申请、用户账号密码、产品是否上 OAuth）：写入 state「待你处理」，告诉用户缺什么。
- 不挡后面任务：先做下一项；用户说那个问题好了再回来。
- 挡后面任务：停在这一项，等用户。

不要假装线上已验证。没有浏览器或 Actions 权限时，写明没验证什么。

## 何时停

同时满足再停，并写「shipin 本轮结束」：

- state 里没有未完成的 P0 / P1
- 本轮改动的测试和（如有部署）线上抽查已通过
- 没有未告知用户的阻塞项

P2 可留到用户再次调用 shipin。用户说停就立即停。

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
