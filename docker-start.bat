@echo off
chcp 65001 >nul
setlocal

echo =====================================================
echo   SimpleWMS  -  Docker Compose 启动
echo   项目: simplewms
echo   启动后: http://localhost/
echo   MySQL : localhost:3306 (user wms / pass wmspass)
echo   Backend API 直连: http://localhost:8080/
echo =====================================================

where docker >nul 2>nul
if errorlevel 1 (
    echo [FAIL] 未检测到 docker,请先安装 Docker Desktop
    exit /b 1
)

docker compose version >nul 2>nul
if errorlevel 1 (
    echo [FAIL] 未检测到 docker compose 插件
    echo        请升级到 Docker Desktop 3.x+,或在 Linux 上安装 docker-compose-plugin
    exit /b 1
)

echo [INFO] 构建并启动所有服务 ...
docker compose up -d --build
if errorlevel 1 (
    echo [FAIL] 启动失败,请检查上方日志
    exit /b 1
)

echo.
echo [INFO] 等待服务就绪 ...
timeout /t 8 /nobreak >nul

echo.
echo [INFO] 服务状态:
docker compose ps

echo.
echo [INFO] 健康检查:
echo   仪表盘 API : curl http://localhost:8080/api/dashboard/stats
echo   主页       : http://localhost/
echo   查看日志   : docker compose logs -f
echo   停止       : docker-stop.bat
endlocal