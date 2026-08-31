# youhua 状态

更新时间：2026-08-31 10:17

## 进行中

- P2 行情/ETF 入库改用 IngestTokens

## 计划

### P0
- （无）

### P1
- （无）

### P2
- 行情/ETF 入库改用 IngestTokens，并补测试（进行中）
- 前端补上 test 脚本（tsc --noEmit）
- CI setup-java 升到 v5
- TopicGenerationStatusService 补 ready 不被降级的测试

## 已完成

- README 订阅说明改为按主题+时刻，并补上 V12
- `096045f` 入库 / poller token 校验
- `8643f4e` 健康检查带上推送加密密钥
- `de41e9c` 对话隔离 + 测试推送用个人简报
- `3fb3a2a` 无资讯/失败主题不再每分钟重跑
- `526354c` 部署与 poller / V12
- `7854e9d` 没有订阅时首页不再假装下次 08:00 推送
- `8750417` README V12
- `8937a3f` 普通用户对话/首页文案不再写成全站科技日报（线上已抽查）

## 待你处理

（无）
