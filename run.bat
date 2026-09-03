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
echo   数据库: H2 文件 ./data/wmsdb (首次启动自动建表+演示数据)
echo   H2 控制台: http://localhost:8080/h2-console
echo   停止: 直接关闭此窗口或 Ctrl+C
echo =====================================================

java -jar "%JAR%" %*

endlocal