# youhua 项目参考（BriefMind / Bot-Brief）

## 仓库与发布

- 产品名 BriefMind，仓库 Bot-Brief，工作区常见路径 `d:\Programming\Cursor\project\brief`
- 发布分支：`main`。推送后 **Deploy on server** 在腾讯云 self-hosted runner（label `bot-brief`）构建
- 路径触发：`frontend/**`、`backend/**`、`automation/**`、`docker-compose.yml`、相关 `scripts/` 与 workflow
- 旧的 **Deploy Frontend / Deploy Backend** 仅手动，不要当日常路径
- 线上演示：`http://124.222.194.103/`
- 本机健康：前端 `http://127.0.0.1:8080/`，后端 `http://127.0.0.1:8081/api/health`

部署未绿时常见原因：国内拉 GitHub 要用代理包；后端缺本地镜像 `eclipse-temurin:17-jre`、`maven:3.9-eclipse-temurin-17`、`mysql:8.0`；MySQL 缺列（订阅表必须有 `user_id`、`topic_schedules`、早晚时间列）。生产库补列用 Navicat 时不要写 `ADD COLUMN IF NOT EXISTS`（老 MySQL 会语法错误）。

## 内容隔离（不要改错）

- **我的简报**（`edition=personal`，`user_id` 为当前用户）：按订阅生成，新用户默认空
- **ETF / A股观察**（`market_watch_*`，`user_id=0`）：当前设计为登录用户可见的公共内容
- Demo 只读合成数据。未登录用户不能看别人的个人简报
- 改隔离策略必须用户明确要求，不要当 youhua 默认项

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
- 在 youhua 循环里把 ETF 公共观察改成私有（除非用户本轮明确说）
- 把生产库当玩具：破坏性 SQL 先说明再执行；用户常用 Navicat 连 `ai_daily`
