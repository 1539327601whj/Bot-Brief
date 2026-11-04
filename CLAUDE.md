# BriefMind / Bot-Brief 项目约定

> 这份文件每次会话都值得读。项目状态与决策记录见 `docs/PROJECT_STATE.md`、`docs/DECISIONS.md`。

## 1. 项目是什么

- 产品名 **BriefMind**，仓库名 **Bot-Brief**。
- 前后端分离的全栈工作台：AI 简报、ETF/A 股行情与估值、订阅推送、内容增长、店铺分析。
- 与当前投资功能相关的模块：
  - **代码查询**：`/code-lookup`，规则结论 + 六维评分 + 参考区间 + AI 解读。
  - **低估精选**：`/stock-pick`，按需点击实时筛选，不推送、不订阅、不落用户结果。
  - **ETF 日报**：工作日 18:00 一次，ETF 本身不给操作建议，附两只 A 股候选。
  - **数据仓库**：Python `automation/warehouse` 负责盘后同步，Java 只读。

## 2. 目录与职责

| 目录 | 职责 |
| --- | --- |
| `backend/` | Java 17 + Spring Boot 3.2，REST API、规则引擎、鉴权、定时任务 |
| `frontend/` | React 18 + TypeScript + Vite，页面与图表 |
| `automation/` | Python 3.11，RSS/行情/估值同步、日报生成、warehouse 批处理 |
| `backend/sql/` | 手工版本化 SQL；没有 Flyway/Liquibase，执行顺序见 README |
| `docs/` | 项目状态、决策记录、后续 runbook |
| `.cursor/*-state.md` | 功能级会话记忆，改对应模块前先读 |

## 3. 常用命令

```bash
# 后端测试（在 backend/ 下）
mvn test
mvn -Dtest=ScreeningRulesTest test
mvn -Dtest=CodeLookupV2*Test test

# 前端类型检查/构建（在 frontend/ 下）
npm install
npm run test        # 目前只有 tsc --noEmit
npm run build

# Python 测试（仓库根目录）
python -m pytest automation/tests -q
python -m pytest automation/tests/test_warehouse_eastmoney.py -q
```

> Lombok 类改完后如果测试报 `Flexible Constructor Bodies` 一类怪错，先 `mvn clean` 再跑。

## 4. 必须知道的环境变量

| 变量 | 用途 |
| --- | --- |
| `DB_*` / `SPRING_DATASOURCE_*` | MySQL 连接 |
| `JWT_SECRET` | JWT 签名；生产必须覆盖默认值 |
| `ADMIN_PASSWORD` | 管理员初始密码；生产必须显式配置 |
| `DEEPSEEK_API_KEY` | AI 解读/日报；没有时功能降级，不阻塞规则结论 |
| `REPORT_INGEST_TOKEN` | Python 写入报告/行情的令牌 |
| `PUSH_CHANNEL_ENCRYPTION_KEY` | 推送凭据加密 |
| `WAREHOUSE_TRUST_ENV` | 国内行情链路固定 `false`，避免系统代理 7890 |

## 5. 已知陷阱

1. **本机系统代理 7890**：会让国内行情域名失败。warehouse 默认 `trust_env=False`；新增 HTTP 客户端也要显式关闭。
2. **东财按 IP 限流，绝不自动重试**；封禁按域名族生效（`push2` 挂不代表 `datacenter-web` 也挂）。
3. **不要用东财 `clist`**，会触发 IP 封禁；全市场清单走 `datacenter-web` 的 `RPT_VALUEANALYSIS_DET`。
4. 腾讯日线单页最多约 800 根，取 2600 根要翻页。
5. `V15__investment_warehouse.sql` 等迁移需要手工执行；`WarehouseSchemaGuard` 只检查、不建表，缺表时 v2 返回 503。
6. Java 与 Python 的限流冷却 key 尚未完全统一：交互是 `eastmoney:interactive`，批量是 `eastmoney:batch`，域名族另有 key。改限流前先读 `SharedRateLimitStore` 与 `automation/warehouse/rate_limit.py`。
7. 前端主题颜色只能用 `frontend/src/index.css` 的语义 token，不要在组件里写死颜色。
8. 后端 `CodeLookupV2DTO` 与前端 `frontend/src/pages/codeLookup/types.ts` 是手工镜像，改一处必须同步另一处，并更新 JSON 契约测试。

## 6. 用户投资画像（产品前提）

- 偏进取，使用闲钱，可长期甚至永久持有，以长期收益为目标。
- 能接受短期下跌和较大回撤，但要求长期向上（类似纳斯达克）。
- **不能选夕阳产业**：现在不行、未来也没有很大发展空间、回撤很大且无法确定能否收回的标的。
- 免费数据源优先；DeepSeek 可自由使用；付费数据源必须说明原因和收益。
- 结论偏好：
  - 长期明显优于短期 → 直接给长期结论和理由；
  - 短期明显优于长期 → 直接给短期结论和理由；
  - 判断不出或差不多 → 两者都简述，再让用户点长期/短期深入。
- 收益百分比可以给，但必须说明是历史统计/不保证；行动结论必须清楚。

## 7. 产品边界

- 低估精选：按需点击实时算，不做推送、订阅、用户结果落库。
- 代码查询：按需查询，不记录用户级查询日志；缺失数据要写原因，不补 0。
- ETF 日报：工作日 18:00 一次；ETF 无操作建议，保留清晰行情版式并附两只 A 股候选。
- 所有投资页面保留免责声明；不承诺收益，不写目标价。

## 8. 工作方式

1. 改规则先改 `automation/agents/stock_screening_rules.md`，再改 Java。
2. 新增数据源先写探针脚本验证字段/限流，再接入；失败要可回退。
3. 不擅自改筛选阈值、迁移顺序、限流策略；需要变更时先写进 `docs/DECISIONS.md`。
4. 每个任务结束更新 `docs/PROJECT_STATE.md`：改了什么、没改什么、下次从哪继续。
5. 前端改动至少跑 `npm run build`；后端改动至少跑相关 `mvn -Dtest=... test`。
