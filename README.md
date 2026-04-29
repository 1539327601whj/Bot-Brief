       # Bot-Brief智能简报平台#

<div align="center">

![Java](https://img.shields.io/badge/Java-17+-ED8B00.svg?style=flat&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-6DB33F.svg?style=flat&logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB.svg?style=flat&logo=react&logoColor=white)
![Vite](https://img.shields.io/badge/Vite-5.0-646CFF.svg?style=flat&logo=vite&logoColor=white)
![DeepSeek](https://img.shields.io/badge/DeepSeek-AI-1E90FF.svg?style=flat)
![License](https://img.shields.io/badge/License-MIT-green.svg)

**基于 DeepSeek 大模型的智能资讯聚合与简报生成平台**

在线演示：https://qaq.goodhappy.top/

</div>

---

## 📖 项目介绍

**Bot-Brief** 是一款面向企业团队的 AI 智能简报平台，通过自动化数据采集、智能内容摘要、定时消息推送的完整闭环，帮助团队高效获取每日精选 AI 资讯。

系统每日自动抓取主流科技媒体的最新 AI 资讯，借助 **DeepSeek** 大语言模型的强大自然语言处理能力，对海量信息进行智能分析、要点提取与结构化摘要，生成高质量的每日简报，并通过 **企业微信机器人** 准时推送到团队群组，同时在前端 Dashboard 同步展示，实现多端信息互通。

### 核心价值

| 价值点 | 说明 |
|--------|------|
| **信息降噪** | 从海量资讯中智能筛选高价值 AI 内容，过滤低质信息 |
| **AI 摘要** | 基于 DeepSeek 深度理解文章核心，生成精准简练的要点摘要 |
| **定时触达** | 通过腾讯云 SCF 实现早 8 点、晚 8 点准时推送，无需人工干预 |
| **多端同步** | 企业微信推送与前端 Dashboard 实时同步，随时随地查看 |
| **历史追溯** | 支持查看历史简报，构建团队知识库 |

---

## ✨ 核心特性

### 🕷️ 智能爬虫采集（Java/Python）

- **多源并行抓取**：基于 Java/Python 多线程技术，同时采集多个主流科技媒体（36氪、虎嗅、IT之家等）
- **智能内容解析**：自动提取标题、正文、发布时间、分类等关键信息
- **反爬策略应对**：模拟真实浏览器请求头，处理动态加载内容
- **数据清洗去重**：基于 SimHash 等算法实现内容去重，保证简报多样性

### 🤖 DeepSeek AI 智能处理

- **语义理解**：基于 DeepSeek 强大语义理解能力，精准把握文章核心观点
- **智能摘要生成**：将长篇文章压缩为结构化的精华摘要，保留关键信息
- **内容分类**：自动识别文章主题分类（技术突破、产品发布、行业动态等）
- **质量评分**：对抓取内容进行质量评估，优先推送高价值资讯

### ⏰ 定时任务调度（腾讯云 SCF + GitHub Actions）

- **腾讯云 SCF 无服务器函数**：利用腾讯云 Serverless 云函数实现定时触发
- **双时段推送**：支持早间版（08:00）和晚间版（20:00）自动推送
- **GitHub Actions 自动化**：工作流编排，实现数据采集→AI处理→消息推送全链路
- **失败告警机制**：任务失败时自动重试，并发送告警通知

### 📢 企业微信集成

- **Webhook 机器人推送**：通过企业微信机器人 API 将简报推送到指定群组
- **富文本消息**：支持 Markdown 格式，包含标题、摘要、原文链接
- **@全员通知**：重要简报可配置 @所有人 提醒
- **交互式卡片**：支持点击跳转原文，查看详情

### 🎨 现代化前端（React 18 + Vite）

- **首页概览**：今日简报卡片展示，支持快速浏览
- **历史简报**：按日期查看历史简报，支持搜索与筛选
- **订阅管理**：配置推送时间、推送渠道、内容偏好
- **AI 对话**：基于历史简报知识库的智能问答助手

### 🏗️ 后端服务（Spring Boot 3.2）

- **RESTful API**：提供完整的简报查询、订阅管理、用户管理接口
- **MyBatis-Plus ORM**：高效数据库操作，支持分页查询与动态 SQL
- **TiDB Serverless**：云原生分布式数据库，支持水平扩展与高可用
- **分层架构**：Controller → Service → Mapper 三层职责清晰

---

## 🛠️ 技术栈

### 前端

| 技术 | 版本 | 说明 |
|------|------|------|
| **React** | 18.x | 核心 UI 框架，函数组件 + Hooks 开发模式 |
| **TypeScript** | 5.x | 类型安全的 JavaScript 超集，编译期错误检查 |
| **Vite** | 5.x | 下一代前端构建工具，极速冷启动 |
| **React Router** | 6.x | 客户端路由管理，支持懒加载 |
| **Ant Design** | 5.x | 企业级 UI 组件库 |

### 后端

| 技术 | 版本 | 说明 |
|------|------|------|
| **Java** | 17+ | 主要开发语言，LTS 长期支持版本 |
| **Spring Boot** | 3.2.x | 核心 Web 框架，自动配置、内嵌服务器 |
| **Spring Web** | - | RESTful API 开发 |
| **MyBatis-Plus** | 3.5.x | ORM 持久层框架，强大的 CRUD 增强 |
| **MyBatis-Plus 分页插件** | - | 物理分页，支持多种数据库 |
| **TiDB Serverless** | - | 云原生分布式数据库，MySQL 兼容 |
| **Maven** | 3.8+ | 项目构建与依赖管理 |

### AI 与数据处理

| 技术 | 说明 |
|------|------|
| **DeepSeek API** | 大语言模型（智能摘要、内容理解） |
| **Python 3.10+** | 爬虫脚本开发语言 |
| **requests** | HTTP 请求库 |
| **BeautifulSoup4** | HTML 解析库 |
| **lxml** | 高性能 XML/HTML 解析 |

### 云服务与部署

| 平台 | 用途 |
|------|------|
| **腾讯云 SCF** | Serverless 云函数，定时触发任务 |
| **GitHub Actions** | CI/CD 与定时工作流编排 |
| **Vercel** | 前端静态网站托管，全球 CDN 加速 |
| **Render** | 后端容器化部署，自动扩缩容 |
| **TiDB Cloud** | Serverless 数据库服务 |

### 消息推送

| 技术 | 说明 |
|------|------|
| **企业微信 Webhook** | 群机器人消息推送 |
| **Markdown 消息** | 富文本格式支持 |

---

## 🏗️ 系统架构

### 整体架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           定时触发层                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    腾讯云 SCF (Serverless)                           │   │
│  │              ┌──────────────┐              ┌──────────────┐         │   │
│  │              │  早 8:00 触发  │              │  晚 8:00 触发  │         │   │
│  │              └──────┬───────┘              └──────┬───────┘         │   │
│  └─────────────────────┼─────────────────────────────┼─────────────────┘   │
└────────────────────────┼─────────────────────────────┼─────────────────────┘
                         │                             │
                         ▼                             ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         GitHub Actions 工作流                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  1. 检出代码 → 2. 安装依赖 → 3. 执行爬虫 → 4. AI 摘要 → 5. 推送消息   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        数据采集层 (Python/Java)                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐                     │
│  │  36氪爬虫  │  │  虎嗅爬虫  │  │ IT之家爬虫 │  │  更多...  │                     │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘                     │
│       │             │             │             │                            │
│       └─────────────┴──────┬──────┴─────────────┘                            │
│                            ▼                                                │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        数据清洗与去重                                │   │
│  │              (SimHash 去重 · 内容过滤 · 质量评分)                     │   │
│  └────────────────────────┬────────────────────────────────────────────┘   │
└───────────────────────────┼─────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         AI 处理层 (DeepSeek)                                  │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    DeepSeek API 调用                                 │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                  │   │
│  │  │  语义理解    │→│  要点提取    │→│  摘要生成    │                  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘                  │   │
│  └────────────────────────┬────────────────────────────────────────────┘   │
└───────────────────────────┼─────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      消息推送层 (企业微信 Webhook)                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐          │   │
│  │  │ Markdown 排版 │ → │  卡片消息组装  │ → │ Webhook 推送  │          │   │
│  │  └──────────────┘    └──────────────┘    └──────────────┘          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      数据持久化层 (Java Spring Boot)                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Spring Boot 3.2 API 服务                          │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                  │   │
│  │  │  Controller │→│   Service   │→│    Mapper   │                  │   │
│  │  │  (REST API) │  │ (业务逻辑)   │  │ (数据访问)   │                  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘                  │   │
│  │         │                  │                  │                     │   │
│  │         └──────────────────┴──────────────────┘                     │   │
│  │                            ▼                                        │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │              MyBatis-Plus ORM 框架                           │   │   │
│  │  │       (分页插件 · 条件构造器 · 代码生成器)                     │   │   │
│  │  └────────────────────────┬────────────────────────────────────┘   │   │
│  └───────────────────────────┼────────────────────────────────────────┘   │
└──────────────────────────────┼──────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                        数据存储层 (TiDB Serverless)                           │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                  │   │
│  │  │  news 表    │  │ briefings 表 │  │ subscribers表│                  │   │
│  │  │ (新闻原文)   │  │ (简报内容)   │  │ (订阅配置)   │                  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘                  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      前端展示层 (React 18 + Vite)                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    React 18 单页应用                                 │   │
│  │                                                                     │   │
│  │   ┌──────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐        │   │
│  │   │ 首页概览  │   │ 历史简报  │   │ 订阅管理  │   │ AI 对话  │        │   │
│  │   │(今日简报) │   │(按日期查看)│   │(推送配置) │   │(智能问答) │        │   │
│  │   └──────────┘   └──────────┘   └──────────┘   └──────────┘        │   │
│  │                                                                     │   │
│  │   ┌─────────────────────────────────────────────────────────────┐  │   │
│  │   │              React Router 6 路由管理                         │  │   │
│  │   └─────────────────────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 数据流

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐
│ 定时触发  │ → │ 爬虫采集  │ → │ AI 摘要  │ → │ 消息推送  │ → │ 前端同步  │
└──────────┘    └──────────┘    └──────────┘    └──────────┘    └──────────┘
   SCF          Python/Java     DeepSeek API    企业微信        React + API
```

### 技术架构特点

1. **前后端分离**：React 前端与 Spring Boot 后端独立部署，通过 REST API 通信
2. **Serverless 定时**：腾讯云 SCF 实现低成本、高可靠的定时触发
3. **云原生数据库**：TiDB Serverless 免运维、自动扩缩容
4. **AI 驱动**：DeepSeek 大模型赋能内容理解与生成
5. **多端触达**：企业微信 + Web Dashboard 双端同步

---

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/GoodHappy666/ai-daily-bot.git
cd ai-daily-bot
```

### 2. 前端启动

```bash
# 安装依赖
npm install

# 开发模式
npm run dev

# 生产构建
npm run build
```

### 3. 后端部署

后端采用 **Spring Boot 3.2 + MyBatis-Plus** 架构：

```bash
cd backend

# 本地运行（需配置 TiDB 连接）
mvn spring-boot:run

# 打包构建
mvn clean package

# Docker 构建
docker build -t briefmind-backend .
```

### 4. 配置环境变量

#### 前端 (.env)

```bash
VITE_API_BASE=https://ai-daily-backend.onrender.com
```

#### 后端 (application.yml)

```yaml
spring:
  datasource:
    url: jdbc:mysql://gateway01.us-west-2.prod.aws.tidbcloud.com:4000/ai_daily
    username: your_username
    password: your_password
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis-plus:
  configuration:
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl
  global-config:
    db-config:
      id-type: auto
```

#### 爬虫脚本 (.env)

```bash
DEEPSEEK_API_KEY=your_deepseek_api_key
API_BASE_URL=https://ai-daily-backend.onrender.com
API_TOKEN=your_api_token
WECHAT_WEBHOOK_URL=https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=your_key
```

---

## 📋 功能模块详解

### 1. 首页概览

- 今日简报卡片展示
- 关键数据指标（今日资讯数、简报数、阅读数）
- 快捷操作入口

### 2. 历史简报

- 按日期查看历史简报
- 支持关键词搜索
- 按分类筛选（技术/产品/行业）
- 分页加载

### 3. 订阅管理

- 推送时间配置（早/晚/全天）
- 推送渠道选择（企业微信/邮件）
- 内容偏好设置（技术领域筛选）
- 订阅开关控制

### 4. AI 对话

- 基于历史简报的智能问答
- 自然语言查询（"上周有哪些大模型发布？"）
- 原文引用与跳转

---

## ⚙️ 核心配置

### 腾讯云 SCF 定时配置

```yaml
# 早 8 点触发
cron: 0 0 8 * * *

# 晚 8 点触发  
cron: 0 0 20 * * *
```

### GitHub Actions 工作流

工作流文件位于 `.github/workflows/daily.yml`：

```yaml
name: Daily Briefing

on:
  schedule:
    - cron: '0 0 * * *'  # UTC 0:00 = 北京时间 8:00
  workflow_dispatch:  # 支持手动触发

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - name: Set up Python
        uses: actions/setup-python@v4
        with:
          python-version: '3.10'
      - name: Install dependencies
        run: pip install -r requirements.txt
      - name: Run crawler and push
        env:
          DEEPSEEK_API_KEY: ${{ secrets.DEEPSEEK_API_KEY }}
          WECHAT_WEBHOOK_URL: ${{ secrets.WECHAT_WEBHOOK_URL }}
        run: python scripts/main.py
```

---

## 📁 项目结构

```
ai-daily-bot/
├── .github/
│   └── workflows/
│       └── daily.yml              # GitHub Actions 定时任务
├── backend/                        # Spring Boot 后端
│   ├── src/main/java/com/aidaily/
│   │   ├── controller/             # REST API 控制器
│   │   │   ├── BriefingController.java
│   │   │   ├── NewsController.java
│   │   │   └── SubscriberController.java
│   │   ├── service/                # 业务逻辑层
│   │   │   ├── BriefingService.java
│   │   │   └── NewsService.java
│   │   ├── mapper/                 # MyBatis 数据访问层
│   │   │   ├── BriefingMapper.java
│   │   │   └── NewsMapper.java
│   │   ├── entity/                 # 实体类
│   │   │   ├── Briefing.java
│   │   │   └── News.java
│   │   └── config/                 # 配置类
│   │       └── MybatisPlusConfig.java  # 分页插件配置
│   └── src/main/resources/
│       └── application.yml         # 配置文件
├── frontend/                       # React + Vite 前端
│   ├── src/
│   │   ├── components/             # 公共组件
│   │   ├── pages/                  # 页面组件
│   │   │   ├── Dashboard.tsx       # 首页概览
│   │   │   ├── History.tsx         # 历史简报
│   │   │   ├── Subscribe.tsx       # 订阅管理
│   │   │   └── Chat.tsx            # AI 对话
│   │   ├── api/                    # API 封装
│   │   └── App.tsx
│   ├── public/                     # 静态资源
│   │   └── bg.png                  # 背景图片
│   └── package.json
├── scripts/                        # Python 爬虫脚本
│   ├── sources/                    # 数据源爬虫
│   │   ├── base.py                 # 爬虫基类
│   │   ├── kr36.py                 # 36氪
│   │   ├── huxiu.py                # 虎嗅
│   │   └── ithome.py               # IT之家
│   ├── ai/                         # AI 处理模块
│   │   └── summarizer.py           # DeepSeek 摘要生成
│   └── main.py                     # 程序入口
├── sql/                            # 数据库脚本
│   └── init_tidb.sql               # TiDB 初始化
├── README.md
└── package.json
```

---

## 🔧 技术亮点

### 后端（Java Spring Boot）

- **Spring Boot 3.2**：采用最新 LTS 版本，支持虚拟线程与 GraalVM 原生编译
- **MyBatis-Plus 3.5**：强大的 CRUD 增强，内置分页插件与条件构造器
- **分层架构**：Controller → Service → Mapper 三层职责清晰，便于维护扩展
- **RESTful API 设计**：遵循 REST 规范，支持分页、过滤、排序等高级查询
- **跨域支持**：配置 CORS 支持前端跨域访问

### 前端（React 18 + Vite）

- **React 18 并发特性**：Suspense、Transition 等新特性提升用户体验
- **TypeScript 类型安全**：编译期检查，减少运行时错误
- **Vite 5 极速构建**：基于 ESM 的开发服务器，冷启动 < 100ms
- **组件化设计**：高复用、低耦合的组件架构
- **响应式布局**：适配桌面端与移动端多端访问

### AI 处理（DeepSeek）

- **智能摘要**：长文本压缩为精华摘要，保留核心信息
- **语义理解**：深度理解文章语义，识别关键实体与关系
- **内容分类**：自动识别文章主题，实现智能分类

### 云服务架构

- **腾讯云 SCF**：Serverless 架构，按需付费，自动扩缩容
- **TiDB Serverless**：云原生分布式数据库，兼容 MySQL 协议
- **GitHub Actions**：免费的 CI/CD 与定时任务调度
- **Vercel + Render**：免费的前后端托管方案

---

## 📊 数据源

当前已集成的资讯来源：

| 媒体 | 网址 | 分类 | 技术方案 |
|------|------|------|----------|
| 36氪 | 36kr.com | 科技/创业/投资 | Python + requests |
| 虎嗅 | huxiu.com | 科技/商业/深度 | Python + BeautifulSoup |
| IT之家 | ithome.com | 科技/数码/快讯 | Python + lxml |

> 💡 扩展数据源：参考 `scripts/sources/` 目录下的现有实现创建新的爬虫模块

---

## 📝 更新日志

### v1.0.0 (2024-04)

- ✅ 基础爬虫采集功能
- ✅ DeepSeek AI 摘要生成
- ✅ 企业微信消息推送
- ✅ Spring Boot 后端 API
- ✅ React 前端 Dashboard
- ✅ 腾讯云 SCF 定时触发

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

---

## 📜 许可证

本项目基于 [MIT License](LICENSE) 开源，欢迎 Star、Fork 与贡献！

---

## 🙏 致谢

- [DeepSeek](https://deepseek.com/) - 强大的大语言模型支持
- [Spring Boot](https://spring.io/projects/spring-boot) - 优秀的 Java Web 框架
- [React](https://react.dev/) - 流行的前端框架
- [Vite](https://vitejs.dev/) - 极速的前端构建工具
- [TiDB Cloud](https://tidbcloud.com/) - 免费云原生数据库
- [腾讯云 SCF](https://cloud.tencent.com/product/scf) - Serverless 云函数
- [Render](https://render.com/) - 免费后端托管
- [Vercel](https://vercel.com/) - 免费前端托管

---

<div align="center">

⭐ 如果这个项目对你有帮助，欢迎点个 Star！

</div>
