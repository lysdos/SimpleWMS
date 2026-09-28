# python:3.9 早已 EOL(2025-10 停止安全更新),换成仍在维护的 3.12 slim。
# 依赖仍是 requirements.txt 里钉住的 Flask 2.3 / Werkzeug 2.3,装得动、跑得起来。
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# 非 root 运行:这个进程只做"连读卡器 TCP + 转调 HTTP",不需要任何特权;
# 容器里以 root 跑,一旦被穿透就是宿主侧的高权限。
RUN useradd --create-home --shell /usr/sbin/nologin uhf \
    && chown -R uhf:uhf /app
USER uhf

EXPOSE 5000

CMD ["python", "-u", "app.py"]
