# youhua 项目参考（BriefMind / Bot-Brief）

## 仓库与发布

- 产品名 BriefMind，仓库 Bot-Brief，工作区常见路径 `d:\Programming\Cursor\project\brief`
- 发布分支：`main`。推送后 **Deploy on server** 在腾讯云 self-hosted runner（label `bot-brief`）构建
- 路径触发：`frontend/**`、`backend/**`、`automation/**`、`docker-compose.yml`、相关 `scripts/` 与 workflow
- 旧的 **Deploy Frontend / Deploy Backend** 仅手动，不要当日常路径
- 线上演示：`http://124.222.194.103/`
- 本机健康：前端 `http://127.0.0.1:8080/`，后端 `http://127.0.0.1:8081/api/health`

部署未绿时常见原因：国内拉 GitHub 要用代理包；后端缺本地镜像 `eclipse-temurin:17-jre`、`maven:3.9-eclipse-temurin-17`、`mysql:8.0`。`subscription` 缺 `user_id` / 早晚时间 / `topic_schedules` 时，后端启动会按 information_schema 自动补列（不要在 Navicat 里写 `ADD COLUMN IF NOT EXISTS`）。`topic_sections`、`reports.user_id` 等仍要手工跑对应 V10+ SQL，发布脚本会硬检查。

## 内容隔离（不要改错）

- **我的简报**（`edition=personal`，`user_id` 为当前用户）：按订阅生成，新用户默认空
- **公共内容**（AI 早晚报 + `market_watch_*` ETF/A股，`user_id=0`）：仅管理员和 Demo 可见
- 普通用户只能看到自己订阅生成的个人简报
- Demo 只读合成数据，可见内容和管理员类似。未登录用户不能看别人的个人简报
- 改隔离策略必须用户明确要求，不要当 youhua 默认项
- 短视频账号绑定 / 内容增长交给 **shipin** skill，不要在 youhua 里顺手大改
- 店铺分析交给 **dianpu** skill，不要在 youhua 里顺手大改

## 自测命令

在仓库根目录。PowerShell 里 `mvn -Dtest=A,B` 必须给 `-Dtest` 加引号。

```powershell
npm --prefix frontend test
mvn -f backend/pom.xml test
python -m unittest discover -s automation/tests -p "test_*.py" -v
```

改动面小时跑相关测试即可，不要无故跳过该层测试。

## 线上抽查

部署绿了之后，用浏览器打开 `http://124.222.194.103/`，按这次改动走主路径：登录/刷新、点菜单、保存、看报错文案。相关页都看（首页、历史简报、订阅管理等）。只截图不算验证。

抽查登录若没有账号：能看未登录页和公开 Demo 就先看这些，并在 state 里注明未覆盖的登录后路径。

## 不要做

- 提交 `.env`、DeepSeek/JWT/数据库密码、runner token
- 为了「完美」大重构或加无关功能
- 把公共 ETF/早晚报再开放给普通用户（除非用户本轮明确说）
- 把生产库当玩具：破坏性 SQL 先说明再执行；用户常用 Navicat 连 `ai_daily`
