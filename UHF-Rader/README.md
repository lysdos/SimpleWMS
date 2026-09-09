# RFID 读卡器演示程序

基于Python + Flask + TCP的RFID标签读取演示系统。

## 系统架构

```
┌─────────────────┐     TCP (Port 5000)     ┌─────────────────┐
│  RFID Reader    │ ─────────────────────> │   Python App    │
│  192.168.1.209  │                       │   (TCP Client)  │
│  (TCP Server)   │                       │  192.168.1.168  │
└─────────────────┘                       └────────┬────────┘
                                                    │
                                          WebSocket│
                                                    ▼
                                          ┌────────────────┐
                                          │    Browser     │
                                          │   Web UI显示    │
                                          └────────────────┘
```

## 配置参数

| 参数 | 值 |
|------|-----|
| 读卡器IP | 192.168.1.209 |
| 读卡器端口 | 5000 |
| 本机Web端口 | 5000 |

## 快速启动

### 方式一：Docker容器（推荐）

```bash
# 构建镜像
docker build -t rfid-demo .

# 运行容器
docker run -d --name rfid-demo --network host rfid-demo
```

或使用docker-compose:

```bash
docker-compose up -d
```

### 方式二：本地运行

```bash
pip install -r requirements.txt
python app.py
```

### 方式三：启动脚本

双击 `start.bat`

## 访问

打开浏览器访问: http://192.168.1.168:5000

## 数据字段

| 字段 | 说明 |
|------|------|
| EPC | 标签唯一标识码 |
| TID | 标签型号ID |
| 天线 | 接收天线编号 (1-4) |
| RSSI | 信号强度 |
| 时间 | 数据接收时间戳 |

## 数据格式

读卡器发送的数据格式（逗号分隔）：
```
设备ID, 类型, ..., EPC, PC, RSSI, 天线ID, ..., TID, ...
   0     1          5     6     7      3           13
```

解析后的字段：
- `result[5]` = EPC
- `result[13]` = TID
- `result[3]` = 天线ID
- `result[7]` = RSSI

## 项目结构

```
D:\Code\UHF-Rader\
├── app.py                  # Flask主程序 + TCP客户端
├── templates/
│   └── index.html          # Web前端页面
├── requirements.txt        # Python依赖
├── Dockerfile             # Docker镜像构建文件
├── docker-compose.yml     # Docker Compose配置
├── start.bat              # 启动脚本
└── README.md              # 使用说明
```

## Docker说明

容器使用host网络模式，直接访问主机的192.168.1.209:5000读卡器。

停止容器：
```bash
docker stop rfid-demo
```

重启容器：
```bash
docker restart rfid-demo
```

查看日志：
```bash
docker logs -f rfid-demo
```