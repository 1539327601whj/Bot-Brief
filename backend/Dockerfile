FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# 走国内镜像。理由和 automation/Dockerfile 里 pip 用国内镜像一样，那边早就做了，这边漏了。
COPY settings.xml /root/.m2/settings.xml

# pom 单独占一层：只有依赖清单变了才重新解析，改 src 不再连带触发。
COPY pom.xml .
# 缓存挂在 repository 子目录、而不是 /root/.m2 —— 挂在上层会把刚 COPY 进去的
# settings.xml 一起遮掉，镜像配置静默失效，退回去直连 central 还不报错。
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests dependency:go-offline

COPY src ./src
# -B 关掉逐条进度输出：不加的话一次构建能刷出八千多行 "Progress (3): 189/268 kB"，
# 真出错的那几行被埋在里面找不到。
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -B -DskipTests clean package

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# 固定 UTF-8：logback 的 ConsoleAppender 自己按 charset 编码，这里是 locale 兜底，
# 不依赖基础镜像的 LANG 设置。
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

EXPOSE 8081

# 编码 flag 直接进 ENTRYPOINT：用 JAVA_TOOL_OPTIONS 会多出 "Picked up JAVA_TOOL_OPTIONS:" 噪音行。
ENTRYPOINT ["java", \
    "-Dfile.encoding=UTF-8", \
    "-Dsun.stdout.encoding=UTF-8", \
    "-Dsun.stderr.encoding=UTF-8", \
    "-jar", "app.jar"]
