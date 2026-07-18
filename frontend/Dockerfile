FROM nginx:alpine

COPY dist/ /usr/share/nginx/html/

RUN printf '%s\n' \
    'server {' \
    '    listen 80;' \
    '' \
    '    location / {' \
    '        root /usr/share/nginx/html;' \
    '        index index.html index.htm;' \
    '        try_files $uri $uri/ /index.html;' \
    '    }' \
    '}' \
    > /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
