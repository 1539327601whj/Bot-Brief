FROM nginx:alpine

# 将前端 build 出来的静态资源 dist 目录，复制到容器内 Nginx 默认的静态文件托管目录
COPY dist/ /usr/share/nginx/html/

# 写入一个简单的 Nginx 配置，用来解决 React Router (History 模式) 刷新页面报 404 的问题
RUN echo 'server { \
    listen 80; \
    location / { \
        root /usr/share/nginx/html; \
        index index.html index.htm; \
        try_files $uri $uri/ /index.html; \
    } \
}' > /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]