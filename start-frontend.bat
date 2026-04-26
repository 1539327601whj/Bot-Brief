@echo off
chcp 65001 >nul
title AI日报后端 - Maven Spring Boot
cd /d "%~dp0..\backend"
echo ====================================
echo  AI 每日简报 - 后端启动中
echo  首次启动需要下载 Maven 依赖
echo  请耐心等待（约 1-3 分钟）
echo ====================================
echo.
mvn spring-boot:run
