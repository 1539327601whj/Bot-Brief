# AI 每日简报 - Spring Boot 后端

> 提供简报存储与查询 API，支持 GitHub Actions 推送简报 + Web 前端读取
> 网页大概长这样，可以看一下 https://brief-mind-frontend.vercel.app/

## 技术栈

- Spring Boot 3.2.0
- MySQL 8.x / TiDB Serverless
- MyBatis-Plus（简化 CRUD）
- Lombok（简化代码）
- Docker（Render 部署）

## API 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/reports` | GitHub Actions 推送新简报 |
| GET | `/api/reports` | 获取简报列表（分页） |
| GET | `/api/reports/{id}` | 获取单条简报详情 |
| GET | `/api/reports/latest` | 获取最新一条简报 |

## 本地开发

```bash
# 1. 创建数据库（本地 MySQL）
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS ai_daily CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# 2. 配置环境变量
export DB_HOST=localhost
export DB_PORT=3306
export DB_NAME=ai_daily
export DB_USER=root
export DB_PASSWORD=your_password

# 3. 启动
mvn spring-boot:run
```

## Render 部署

### 方式一：GitHub 同步部署（推荐）

1. 将代码推送到 GitHub
2. 登录 [Render](https://render.com)
3. New → PostgreSQL → 创建免费数据库（可选，用 TiDB 也行）
4. New → Blueprint → 选择 GitHub 仓库
5. 配置环境变量：
   - `DB_HOST`: TiDB Host（gateway01.us-west-2.prod.aws.tidbcloud.com）
   - `DB_PORT`: 4000
   - `DB_NAME`: ai_daily
   - `DB_USER`: 你的用户名
   - `DB_PASSWORD`: 你的密码
6. 部署完成，获取 URL（如：`https://ai-daily-backend.onrender.com`）

### 方式二：手动部署

1. 构建 Docker 镜像
2. 推送到 Docker Hub
3. 在 Render 创建 Web Service

### 环境变量

| 变量名 | 说明 | 示例 |
|--------|------|------|
| `DB_HOST` | MySQL/TiDB 主机 | gateway01.us-west-2.prod.aws.tidbcloud.com |
| `DB_PORT` | 端口 | 4000 |
| `DB_NAME` | 数据库名 | ai_daily |
| `DB_USER` | 用户名 | 2mdpH9MkQ28H3P9.root |
| `DB_PASSWORD` | 密码 | ****** |

## Render 免费版限制

- 冷启动延迟约 30 秒（睡醒后首次访问慢）
- 每月 750 小时免费额度
- 30 天无访问会自动休眠

## TiDB Serverless 配置

```yaml
# application.yml 配置示例
spring:
  datasource:
    url: jdbc:mysql://gateway01.us-west-2.prod.aws.tidbcloud.com:4000/ai_daily?sslMode=VERIFY_IDENTITY&serverTimezone=UTC
    username: 你的用户名
    password: 你的密码
    driver-class-name: com.mysql.cj.jdbc.Driver
```

## 本地构建 Docker

```bash
# 构建镜像
docker build -t ai-daily-backend .

# 运行
docker run -p 8080:8080 \
  -e DB_HOST=localhost \
  -e DB_PORT=3306 \
  -e DB_NAME=ai_daily \
  -e DB_USER=root \
  -e DB_PASSWORD=your_password \
  ai-daily-backend
```
