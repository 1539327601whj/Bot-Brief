# BriefMind - AI 智能简报生成系统

<div align="center">

![Java](https://img.shields.io/badge/Java-17+-orange.svg)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-blue.svg)
![React](https://img.shields.io/badge/React-18-61dafb.svg)
![Vite](https://img.shields.io/badge/Vite-5.0-646cff.svg)
![License](https://img.shields.io/badge/License-MIT-green.svg)
![Last Commit](https://img.shields.io/github/last-commit/GoodHappy666/ai-daily-bot)

**基于 DeepSeek 大模型的每日资讯智能聚合与简报生成系统**

[**在线演示**](https://project-mu-jet.vercel.app/) · [**后端 API**](https://ai-daily-backend.onrender.com/) · [**项目源码**](https://github.com/GoodHappy666/ai-daily-bot)

</div>

---

## 📖 项目介绍

**BriefMind** 是一款基于人工智能技术的资讯聚合与智能简报生成平台。系统每日自动抓取主流科技媒体的最新资讯，借助 DeepSeek 大语言模型的强大自然语言处理能力，对海量信息进行智能分析、要点提取与结构化摘要，生成高质量的个性化每日简报。

### 核心价值

- **海量信息过滤**：从繁杂的科技资讯中智能筛选高价值内容
- **AI 智能摘要**：基于 DeepSeek 理解文章核心，生成精准简练的摘要
- **RAG 智能问答**：基于历史简报构建知识库，支持自然语言问答
- **多时段订阅**：支持早间版/晚间版等多种订阅模式

---

## ✨ 核心特性

### 🚀 自动化数据采集

- **多源并行抓取**：同时采集多个主流科技媒体（36氪、虎嗅、IT之家等）
- **智能内容解析**：自动提取标题、正文、发布时间、分类等关键信息
- **去重与清洗**：基于 SimHash 等算法实现内容去重，保证简报多样性

### 🤖 DeepSeek AI 智能处理

- **语义理解**：基于 DeepSeek 强大语义理解能力，精准把握文章核心观点
- **智能摘要生成**：将长篇文章压缩为结构化的精华摘要
- **知识关联**：自动识别文章间的关联关系，构建知识图谱
- **RAG 问答引擎**：支持基于历史简报的智能问答，返回精准引用

### ⏰ 定时任务调度

- **GitHub Actions 自动化**：每日北京时间 9:00 自动触发数据采集与简报生成
- **多时段订阅**：支持早间版（08:00）/ 晚间版（20:00）等多种订阅模式
- **失败重试机制**：自动重试确保任务执行成功率

### 📊 数据持久化与 API 服务

- **Spring Boot 微服务架构**：提供 RESTful API，支持简报查询、订阅管理等功能
- **MyBatis-Plus ORM**：高效数据库操作，支持分页查询与动态 SQL
- **TiDB Serverless**：采用云原生分布式数据库，支持水平扩展

### 🎨 现代化前端交互

- **React 18 + TypeScript**：类型安全的前端架构
- **Vite 5 极速构建**：优化的开发体验与生产构建
- **响应式设计**：适配桌面端与移动端多端访问

---

## 🛠️ 技术栈

### 前端

| 技术 | 版本 | 说明 |
|------|------|------|
| **React** | 18.x | 核心 UI 框架 |
| **TypeScript** | 5.x | 类型安全的 JavaScript 超集 |
| **Vite** | 5.x | 下一代前端构建工具 |
| **React Router** | 6.x | 客户端路由管理 |

### 后端

| 技术 | 版本 | 说明 |
|------|------|------|
| **Java** | 17+ | 主要开发语言 |
| **Spring Boot** | 3.2.x | 核心 Web 框架 |
| **MyBatis-Plus** | 3.5.x | ORM 持久层框架 |
| **MyBatis-Plus 分页插件** | - | 高效分页查询 |
| **TiDB Serverless** | - | 云原生分布式数据库 |

### AI 与数据处理

| 技术 | 说明 |
|------|------|
| **DeepSeek** | 大语言模型（智能摘要、RAG 问答） |
| **Python 3.10+** | 爬虫脚本开发语言 |
| **requests + BeautifulSoup4** | HTTP 请求与 HTML 解析 |

### 部署与运维

| 平台 | 用途 |
|------|------|
| **Vercel** | 前端静态网站托管 |
| **Render** | 后端容器化部署 |
| **GitHub Actions** | CI/CD 与定时任务调度 |
| **TiDB Cloud** | Serverless 数据库服务 |

---

## 🏗️ 系统架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        GitHub Actions                            │
│                   (每日北京时间 9:00 自动触发)                      │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Python AI 爬虫服务                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐        │
│  │  36氪    │  │  虎嗅    │  │ IT之家   │  │  更多...  │        │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘        │
│       │             │             │             │               │
│       └─────────────┴──────┬──────┴─────────────┘               │
│                            ▼                                     │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    DeepSeek AI 处理                        │   │
│  │              (智能摘要 · RAG 问答 · 知识图谱)               │   │
│  └────────────────────────┬─────────────────────────────────┘   │
└───────────────────────────┼────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Java Spring Boot API                          │
│         (RESTful API · MyBatis-Plus · TiDB 持久化)              │
└─────────────────────────────┬───────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    TiDB Serverless                               │
│                    (云原生分布式数据库)                            │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                    React + Vite 前端                              │
│              (Vercel CDN 加速 · 响应式设计)                        │
│              https://project-mu-jet.vercel.app                   │
└─────────────────────────────────────────────────────────────────┘
```

### 数据流

1. **采集层**：Python 爬虫每日定时从多个媒体源抓取资讯
2. **处理层**：DeepSeek AI 智能分析，生成结构化简报
3. **服务层**：Spring Boot API 提供数据存储与查询接口
4. **展示层**：React 前端呈现可视化简报与交互界面

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

后端采用 Spring Boot 3.2 + MyBatis-Plus 架构，部署至 Render 容器平台：

```bash
cd backend

# 本地运行（需配置 TiDB 连接）
mvn spring-boot:run

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
```

---

## 📋 数据源

当前已集成的资讯来源：

| 媒体 | 网址 | 分类 |
|------|------|------|
| 36氪 | 36kr.com | 科技/创业/投资 |
| 虎嗅 | huxiu.com | 科技/商业/深度 |
| IT之家 | ithome.com | 科技/数码/快讯 |
| 机器之心 | jiqizhixin.com | AI/机器学习/研究 |

> 💡 扩展数据源：参考 `scripts/sources/` 目录下的现有实现创建新的爬虫模块

---

## ⚙️ 核心配置

### 定时任务配置

工作流文件位于 `.github/workflows/daily.yml`：

```yaml
on:
  schedule:
    - cron: '0 1 * * *'  # UTC 1:00 = 北京时间 9:00
```

### 环境变量

| 变量名 | 必填 | 说明 |
|--------|------|------|
| `DEEPSEEK_API_KEY` | ✅ | DeepSeek API 密钥 |
| `API_BASE_URL` | ✅ | 后端 API 地址 |
| `API_TOKEN` | ✅ | API 访问令牌 |
| `MAX_ARTICLES` | ❌ | 单次最大抓取数（默认 20） |

---

## 📁 项目结构

```
ai-daily-bot/
├── .github/
│   └── workflows/
│       └── daily.yml              # GitHub Actions 定时任务
├── backend/                        # Spring Boot 后端
│   ├── src/main/java/
│   │   └── com/aidaily/
│   │       ├── controller/         # REST API 控制器
│   │       ├── service/            # 业务逻辑层
│   │       ├── mapper/            # MyBatis 数据访问层
│   │       └── entity/             # 实体类
│   └── src/main/resources/
│       └── application.yml         # 配置文件
├── frontend/                       # React + Vite 前端（开发中）
├── scripts/                        # Python 爬虫脚本
│   ├── sources/                    # 数据源爬虫
│   │   ├── base.py                 # 基类
│   │   ├── kr36.py                # 36氪
│   │   ├── huxiu.py               # 虎嗅
│   │   └── ithome.py              # IT之家
│   ├── ai/                         # AI 处理模块
│   │   └── summarizer.py          # DeepSeek 摘要生成
│   └── main.py                     # 程序入口
├── sql/                            # 数据库脚本
│   └── init_tidb.sql              # TiDB 初始化
├── public/                         # 静态资源
├── src/                            # React 源码
├── package.json
├── vite.config.ts
├── tsconfig.json
└── README.md
```

---

## 🔧 技术亮点

### 后端（Java Spring Boot）

- **Spring Boot 3.2**：采用最新 LTS 版本，支持虚拟线程与 GraalVM 原生编译
- **MyBatis-Plus 3.5**：强大的 CRUD 增强，内置分页插件与条件构造器
- **分层架构**：Controller → Service → Mapper 三层职责清晰
- **RESTful API 设计**：遵循 REST 规范，支持分页、过滤等高级查询

### 前端（React）

- **TypeScript 类型安全**：编译期检查，减少运行时错误
- **Vite 5 极速构建**：基于 ESM 的开发服务器，冷启动 < 100ms
- **组件化设计**：高复用、低耦合的组件架构
- **路由懒加载**：优化首屏加载性能

### AI 处理（DeepSeek）

- **智能摘要**：长文本压缩为精华摘要，保留核心信息
- **RAG 问答**：基于向量检索的智能问答，返回精准引用
- **上下文理解**：深度理解文章语义，识别关键实体与关系

---

## 📜 许可证

本项目基于 [MIT License](LICENSE) 开源，欢迎 Star、Fork 与贡献！

---

## 🙏 致谢

- [DeepSeek](https://deepseek.com/) - 强大的大语言模型支持
- [36氪](https://36kr.com/) - 优质科技资讯
- [虎嗅](https://www.huxiu.com/) - 深度商业报道
- [IT之家](https://www.ithome.com/) - 快速科技新闻
- [TiDB Cloud](https://tidbcloud.com/) - 免费云原生数据库
- [Render](https://render.com/) - 免费后端托管
- [Vercel](https://vercel.com/) - 免费前端托管

---

<div align="center">

⭐ 如果这个项目对你有帮助，欢迎点个 Star！

</div>
