FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

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
