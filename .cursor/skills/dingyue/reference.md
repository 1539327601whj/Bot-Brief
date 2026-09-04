# dingyue 模块参考（订阅 / 准时推送）

## 入口

- 页：`/subscription`（侧栏「订阅管理」）、`/notifications`（通知记录）、首页订阅栏
- 线上：`http://124.222.194.103/subscription`
- 通讯录 / 渠道：`/channels`（以仓库实际路由为准）

## 链路（不要拆乱）

1. 用户勾选主题、星期、时刻、渠道，保存 `PUT /api/subscription`
2. poller 按北京时间每分钟拉 `GET /api/reports/due-generations`，提前生成（默认提前 30 分钟；ETF 消化主题更短）
3. 到订阅时刻才上网页（`ReportRelease`），才推渠道（`ScheduledPushTask`）
4. 投递键：`scheduled:{日期}:{HH:mm}:{userId}:{channelId}`；测试推送是 `test:…`
5. 今日进度：`GET /api/subscription/today-status`

生成可以提前。展示和推送必须对准用户选的 `HH:mm`。

## 关键约定

- 时刻窗口：`w00_06` / `w06_12` / `w12_18` / `w18_24`。同一主题同一窗口只能一个时刻。
- 消化主题：`AI科技`（别名科技）走公共早晚报原文；`纳指标普沪深300ETF`（别名 etf / 市场观察）走公共 ETF 晚报原文。有「我想看」则走短段落。
- **ETF 只能订 `w18_24`（默认 18:00）**。前后端保存时都要拦，订阅页不要再给出早晨默认。
- 没绑渠道 = 仅网页。不要回退成「该用户全部启用渠道」。
- 星期在保存时写死。缺星期不要前后端各猜一套（每天 vs 工作日）。
- 管理员可把已订主题标成全站或仅个人（`siteVisible`）。普通用户不能把自定义主题公开。

## 需要你处理（产品）

做在订阅管理，不要单独运维页。

- **当前用户**：自己的最终失败投递、渠道已停变成仅网页、当天时刻过近只能补推、ETF 订错窗口被纠正等。
- **管理员额外**：生成器心跳超时等全站项。
- 每条写清：现象、影响、用户能点的下一步（去通讯录 / 看通知 / 知道了）。
- Agent 能修的代码问题只写 `dingyue-state.md`，不要堆进这块。

## 代码边界（优先只改这些）

- 后端：`Subscription*`、`ScheduledPushTask`、`PushDispatcher`、`PushLog*`、`SubscribedTopicService`、`SubscriptionProgressService`、`ReportRelease`、`ReportWindows`、`DigestTopics`、渠道绑定相关
- 前端：`Subscription.tsx`、`Notifications.tsx`、`pushDisplay.ts`、`topicVisibility.ts`、`reportEdition.ts`、首页里读订阅/投递的部分
- 自动化：`poll_loop.py`、`daily_report.py` 里到期生成 / 补推触发；不要顺手改 ETF 行情算法或科技日报文风

店铺表、短视频、经营日报不是本模块。

## 自测

仓库根目录。PowerShell 里 `-Dtest` 必须加引号。

```powershell
npm --prefix frontend test
mvn -f backend/pom.xml "-Dtest=SubscriptionPreferencesTest,SubscriptionControllerTest,PushDispatcherTest,PushLogServiceImplTest,ScheduledPushTaskTest" test
python -m unittest discover -s automation/tests -p "test_*.py" -v
```

改哪一层跑哪一层，不要无故跳过。

## 线上抽查

部署绿了之后打开 `http://124.222.194.103/subscription`：改一个时刻或开关、保存、看今日进度是否更新、通知记录里是订阅投递还是测试。相关首页栏和通知页也看。只截图不算验证。

## 不要做

- 提交 webhook、邮箱密码、JWT、`.env`
- 把公共 AI/ETF 日报开放给普通用户
- 未授权抓取、假装渠道已经 OAuth
- 为「准时」把生成时刻改成展示时刻（会来不及写）
- 无限补推不设终点
