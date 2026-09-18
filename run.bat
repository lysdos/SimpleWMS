@echo off
chcp 65001 >nul
setlocal

set "JAR=%~dp0target\simple-wms.jar"
if not exist "%JAR%" (
    echo [INFO] 未找到 JAR 包,正在自动构建 ...
    call "%~dp0build.bat"
    if errorlevel 1 exit /b 1
)

echo =====================================================
echo   SimpleWMS  启动中
echo   默认地址: http://localhost:8080/
echo   端口被占用时可改用: run.bat --server.port=9090
echo   数据库: MySQL localhost:23306 (需先 docker compose up -d mysql)
echo   停止: 直接关闭此窗口或 Ctrl+C
echo =====================================================

java -jar "%JAR%" %*

endlocal