@echo off
chcp 65001 >nul
setlocal

echo =====================================================
echo   SimpleWMS  一键构建 (Maven, 支持离线)
echo =====================================================

rem 1. 优先使用项目自带镜像仓库(.m2-repo, 断网也能构建)
rem 2. 回退到用户 Maven 缓存 (~/.m2/repository)
set "REPO=%~dp0.m2-repo"
if not exist "%REPO%" set "REPO=%USERPROFILE%\.m2\repository"

set "MVN=C:\apache-maven-3.9.6\bin\mvn.cmd"
if not exist "%MVN%" (
    set "MVN=mvn"
)

"%MVN%" "-Dmaven.repo.local=%REPO%" -o -DskipTests -B package
if errorlevel 1 (
    echo.
    echo [FAIL] 构建失败,请检查上方日志
    exit /b 1
)

echo.
echo [OK] 构建成功 - JAR 包位于 target\simple-wms.jar
echo 下一步: 运行 run.bat 启动应用 (默认 http://localhost:8080/)
endlocal