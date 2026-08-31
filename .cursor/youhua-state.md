# youhua 状态

更新时间：2026-08-31

## 进行中

（无）

## 计划

### P0

- （无）

### P1

- 管理员在线上勾选「AI科技」「纳指标普沪深300ETF」并绑定微信；关掉旧的科技/ETF 云函数定时，避免和 poller 抢跑
- 下一轮再评估 `reports` 等其它表缺列是否也要启动自愈

### P2

- 订阅保存失败时的可操作错误（缺列已由上一轮兜住）

## 已完成（仓库 HEAD 已有，本轮不重做）

- `e0f363f` 首页未订阅空卡片增加「去订阅管理」；Deploy on server #37 已绿，线上 bundle 已含该文案
- `4a029a8` 历史简报空状态增加「去订阅管理勾选兴趣」；Deploy on server #36 已绿，线上 bundle 已含该文案
- `72be0a6` 启动时自动补齐订阅表缺失列；Deploy on server #35 已绿；`/api/health` UP，未登录订阅接口 401 不再 500
- 普通用户看不到公共 AI 早晚报和 ETF；只看自己的个人简报
- AI科技 / ETF 走订阅 + poller 原文生成
- 订阅页区分 401 与真实 500；JWT 认 `ACCOUNT_NORMAL`
- 腾讯云 self-hosted runner（`bot-brief`）同机构建前后端和 poller
- youhua skill 与参考文档

## 待你处理

- 线上管理员勾选「AI科技」「纳指标普沪深300ETF」，并绑定微信推送
- 关掉旧的科技/ETF 云函数定时（否则和服务器 poller 重复生成）
