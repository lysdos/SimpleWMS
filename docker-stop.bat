@echo off
chcp 65001 >nul
setlocal

echo =====================================================
echo   SimpleWMS  -  Docker Compose 停止
echo =====================================================

docker compose down

echo.
echo 容器已停止.
echo   数据卷 (MySQL) 仍保留: docker volume ls | findstr simplewms
echo   删除数据卷     : docker volume rm simplewms-mysql-data
echo   重新启动       : docker-start.bat
endlocal