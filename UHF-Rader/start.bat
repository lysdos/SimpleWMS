@echo off
chcp 65001 > nul
echo =============================================
echo    RFID 读卡器演示程序 - 启动脚本
echo =============================================
echo.

cd /d "%~dp0"

echo [1/2] 检查Python环境...
python --version > nul 2>&1
if errorlevel 1 (
    echo [错误] 未找到Python，请先安装Python 3.8+
    pause
    exit /b 1
)

echo [2/2] 检查依赖包...
pip show flask > nul 2>&1
if errorlevel 1 (
    echo [提示] 正在安装Python依赖包...
    pip install -r requirements.txt
)

echo.
echo =============================================
echo    启动服务
echo =============================================
echo.
echo [TCP] 连接到读卡器: 192.168.1.209:5000
echo [Web] 启动Web服务: http://192.168.1.168:5000
echo.
echo 按 Ctrl+C 停止服务
echo =============================================

python app.py

pause