---
name: youhua
description: >-
  Continuously audits BriefMind/Bot-Brief, plans prioritized work, implements
  one module at a time, self-tests, commits, pushes main for auto-deploy, then
  verifies the live site and loops. Use when the user says youhua, /youhua,
  优化, or asks to keep improving the project until it is solid.
disable-model-invocation: true
---

# youhua

每次被调用时：先看清项目现状，自己订计划并排优先级，然后一个模块一个模块做。每个模块做完先自测；没问题就写清楚改了什么，提交到本地并推送到远程 GitHub 的 `main`（会自动部署）。部署完成后去网站看效果和功能。没问题再下一步；有问题就对着现象和日志改，再检测、写清楚改了什么、再提交，这样循环。这一步彻底解决后，再执行下一个计划。不是非要人工才能解决的问题，优先自己解决。自己完全解决不了的，告诉用户；不影响后续任务时先做别的，等用户说解决完了再回来。一直循环，直到项目做得很稳、计划里没有未完成的高优先级项，再停。

状态记在 [youhua-state.md](../../youhua-state.md)（没有就创建）。项目细节见 [reference.md](reference.md)。

## 每次启动

1. 读 `youhua-state.md`（有则接着做，不要推倒重来）。
2. 看现状：`git status`、`git log -8`、`git diff`、最近 Actions、打开的模块、已知线上问题。
3. 对照 reference 里的产品边界，列出缺口：坏掉的功能、部署/数据风险、隔离/权限、测试空洞、明显的 UX 问题。
4. 写出或更新计划，按 P0 → P1 → P2 排序。同一时间只执行 **一项** 进行中任务。
5. 把计划写进 `youhua-state.md` 并告诉用户这一轮要做哪一项。

不要同时开多个大改。不要为了「完美」重写无关模块。

## 做一项

1. 只改完成这一项所必需的文件。
2. 自测（能跑的都跑，见 reference）：
   - 前端：`npm --prefix frontend test` 或对该改动最相关的检查
   - 后端：`mvn -f backend/pom.xml test`（Windows 给 `-Dtest` 加引号）
   - Python：`python -m unittest discover -s automation/tests -p "test_*.py" -v`
   - 与 UI 有关：用浏览器工具按真实用户路径点一遍，不要只截一张图
3. 自测不过：先修，再测。不要带着红测试提交。
4. 自测通过：写清改了什么，再提交并推送（见下）。
5. 等部署成功（reference）。失败就看 Actions / 服务器日志，修、测、再推，直到绿或判定为阻塞。
6. 部署成功后打开线上站点，验证**这次改动相关**的页面和相邻会坏的路径。
7. 线上有问题：对着页面现象和日志改 → 自测 → 写清改了什么 → 提交推送 → 再等部署 → 再看网站。未彻底解决前不要开始下一项。
8. 线上没问题：在 state 里勾掉这项，开始下一项。

## 提交和推送

用户调用 youhua 即授权：**每完成一轮可发布的修复，提交并推送到 `origin/main`。**

仍遵守：

- 不改 git config；不用 `-i`；不 `--no-verify`；不 force push `main`
- 不提交 `.env`、密钥、`credentials.json`
- 先 `git status`、`git diff`、`git log -8`，再按仓库近期文风写说明
- 说明写 **为什么**（1–2 句），并列出改了什么
- PowerShell 不要用 bash HEREDOC。用：

```powershell
git add <相关文件>
git commit -m "简短说明。`n`n- 改动1`n- 改动2"
git status
git push origin HEAD:main
```

推送后用 `gh run list --branch main --limit 5`（或打开 Actions）看 **Deploy on server** 和 **市场数据链路测试**。只改了文档/skill、不会触发部署时，跳过等部署，但仍做能做的本地验证。

## 卡住时

- 能自己修的（缺列、测试、UI、日志里的明确异常）：自己修完再走提交循环。
- 完全做不了的（没密码、要生产改密钥、要用户拍板产品方向）：写入 state 的「待你处理」，告诉用户缺什么。
- 不挡后面任务：先做下一项；用户说那个问题好了再回来。
- 挡后面任务：停在这一项，等用户。

不要假装线上已验证。没有浏览器或 Actions 权限时，写明没验证什么，再继续能继续的部分。

## 何时停

同时满足再停，并在回复里写「youhua 本轮结束」：

- state 里没有未完成的 P0 / P1
- 本轮改动的测试和（如有部署）线上抽查已通过
- 没有未告知用户的阻塞项

P2 可留到用户再次调用 youhua。用户说停就立即停。

## 回复格式

每轮结束（一项完成或阻塞）用简短中文：

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
