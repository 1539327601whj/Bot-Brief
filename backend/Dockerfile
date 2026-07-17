name: Bot-Brief Docker CI/CD Pipeline

on:
  push:
    branches:
      - main

permissions:
  packages: write
  contents: read

jobs:
  build-and-push-images:
    runs-on: ubuntu-latest
    steps:
    - name: Checkout Code
      uses: actions/checkout@v3

    # 1. 登录 GitHub 官方私有 Docker 镜像仓库
    - name: Log in to GitHub Container Registry
      uses: docker/login-action@v2
      with:
        registry: ghcr.io
        username: ${{ github.actor }}
        password: ${{ secrets.GITHUB_TOKEN }}

    # ================= 前端：打包 + 制作 Docker 镜像并推送 =================
    - name: Setup Node.js & Build Frontend
      uses: actions/setup-node@v3
      with:
        node-version: 18
        cache: 'npm'
    - name: Build Frontend Assets
      run: |
        npm install
        npm run build

    - name: Build and Push Frontend Image
      uses: docker/build-push-action@v4
      with:
        context: .
        file: ./Dockerfile
        push: true
        tags: ghcr.io/${{ github.repository_owner }}/bot-brief-frontend:latest

    # ================= 后端：直接利用你写好的多阶段构建 Dockerfile 编译并推送 =================
    - name: Build and Push Backend Image
      uses: docker/build-push-action@v4
      with:
        context: ./backend
        file: ./backend/Dockerfile   # 直接走你现有的 Dockerfile，它自己会在镜像里完成编译！
        push: true
        tags: ghcr.io/${{ github.repository_owner }}/bot-brief-backend:latest

  deploy-to-server:
    needs: build-and-push-images
    runs-on: ubuntu-latest
    steps:
    - name: SSH and Run Docker Containers
      uses: appleboy/ssh-action@master
      with:
        host: ${{ secrets.SERVER_IP }}
        username: ${{ secrets.SERVER_USER }}
        password: ${{ secrets.SERVER_PASSWORD }}
        script: |
          # 1. 确保服务器安装了 Docker
          if ! command -v docker &> /dev/null; then
            curl -fsSL https://get.docker.com | bash -s docker --mirror Aliyun
            systemctl start docker
            systemctl enable docker
          fi

          # 2. 登录 GitHub 镜像仓库
          echo "${{ secrets.GITHUB_TOKEN }}" | docker login ghcr.io -u ${{ github.actor }} --password-stdin

          # 3. 停止并删除旧容器
          docker stop bot-brief-frontend || true
          docker rm bot-brief-frontend || true
          docker stop bot-brief-backend || true
          docker rm bot-brief-backend || true

          # 4. 拉取最新镜像
          docker pull ghcr.io/${{ github.repository_owner }}/bot-brief-frontend:latest
          docker pull ghcr.io/${{ github.repository_owner }}/bot-brief-backend:latest

          # 5. 启动前端容器
          docker run -d --name bot-brief-frontend -p 80:80 --restart always ghcr.io/${{ github.repository_owner }}/bot-brief-frontend:latest

          # 6. 启动后端容器（注意：这里端口映射改为了 8081:8081 匹配你的 Dockerfile）
          docker run -d --name bot-brief-backend -p 8081:8081 \
            -e DEEPSEEK_API_KEY="你的实际APIKEY" \
            --restart always \
            ghcr.io/${{ github.repository_owner }}/bot-brief-backend:latest

          # 清理无用镜像
          docker image prune -f
          echo "Docker Deployment completed successfully!"