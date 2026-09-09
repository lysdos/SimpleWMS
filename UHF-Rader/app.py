#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os
import socket
import threading
import time
from flask import Flask, render_template, jsonify, request
from datetime import datetime
import json
import struct

app = Flask(__name__)
# Flask-SocketIO + threading 模式在 demo 场景下会卡住 GIL,改用纯 Flask +
# 前端 JS 轮询的方式(2 秒拉一次 /api/wms/state)。生产可用 eventlet/gevent。
socketio = None  # 保留符号占位,wms_bridge 仍可能引用,但实际不触发 emit

# 读卡器 TCP 目标(从环境读,docker-compose 已注入)
READER_HOST = os.getenv("READER_HOST", "192.168.1.209")
READER_PORT = int(os.getenv("READER_PORT", "5000"))

# WMS 后端地址(容器内是 http://backend:8080)
WMS_BASE_URL = os.getenv("WMS_BASE_URL", "http://127.0.0.1:8080")

# RFID Commands (from SDK Types.cs)
CMD_START_MULTI_EPC = 0x17
CMD_STOP_MULTI_EPC = 0x18

# Protocol Frame Format
FRAME_HEADER = 0xA0  # Protocol header
FRAME_ESCAPE = 0xA1  # Escape byte for data transparency

tag_list = []
tag_list_lock = threading.Lock()
MAX_TAGS = 100
is_connected = False
is_reading = False  # Reading state flag
client_socket = None
client_socket_lock = threading.Lock()

class TagInfo:
    def __init__(self, epc, tid, ant_id, rssi, timestamp):
        self.epc = epc
        self.tid = tid
        self.ant_id = ant_id
        self.rssi = rssi
        self.timestamp = timestamp

    def to_dict(self):
        return {
            'epc': self.epc,
            'tid': self.tid,
            'ant_id': self.ant_id,
            'rssi': self.rssi,
            'timestamp': self.timestamp
        }

def _normalize_epc(raw):
    """把读卡器各种形态的 EPC 归一化为 24 位大写 hex。失败返回 None。

    目标长度 = 24 hex = 12 字节 = 96 bit(标准 UHF Gen2 EPC),
    与 WMS 后端 inventory_item.code(VARCHAR(24))等长。

    输入容忍:
      - 已是 24 hex 字符 → 原样返回
      - 16/12/8 hex 字符 → 右补 0 到 24
      - 12 字节 raw 二进制(每字节 1-2 字符乱码)→ 先 hex 编码再补
    """
    if not raw:
        return None
    s = raw.strip().upper()
    # 已经是 24 hex
    if len(s) == 24 and all(c in "0123456789ABCDEF" for c in s):
        return s
    # 短 hex → 右补 0
    if all(c in "0123456789ABCDEF" for c in s) and len(s) < 24 and len(s) % 2 == 0:
        return s.ljust(24, '0')
    # 短 hex 奇数长度(不该出现,做容错)
    if all(c in "0123456789ABCDEF" for c in s) and len(s) < 24:
        return ("0" + s)[-24:]
    # 含非 hex 字符:按 raw 字节 hex 编码(读卡器在某些固件下不直接给 hex 字符串)
    try:
        h = raw.strip().encode('utf-8').hex().upper()
        if len(h) >= 24:
            return h[-24:]
        if len(h) > 0 and len(h) % 2 == 0:
            return h.rjust(24, '0')
    except Exception:
        return None
    return None

def parse_epc_data(data):
    """Parse EPC data from reader. Handles both comma-separated and binary formats."""
    try:
        data = data.strip()
        if not data:
            return None

        # Try comma-separated format first (ASCII mode)
        result = data.split(',')
        if len(result) >= 14:
            raw_epc = result[5] if result[5] else ""
            tid = result[13] if len(result) > 13 and result[13] else ""
            ant_id = result[3] if result[3] else ""
            rssi = result[7] if len(result) > 7 and result[7] else ""

            epc = _normalize_epc(raw_epc)
            if epc:
                timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                return TagInfo(epc, tid, ant_id, rssi, timestamp)

        # Binary format with ~~ marker
        # Format: ~~<epc_data> where epc_data is raw bytes
        if '~~' in data:
            idx = data.find('~~')
            after_marker = data[idx+2:]  # After ~~
            epc = _normalize_epc(after_marker)
            if epc:
                timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                return TagInfo(epc, "", "1", "", timestamp)

        return None
    except Exception as e:
        print(f"[Parse Error] {e}")
        return None

def build_command_frame(cmd, data=None):
    """Build protocol frame: Header(2) + Length(2) + Command(1) + Data(n) + Checksum(2)"""
    if data is None:
        data = []

    # Calculate payload length: Command(1) + Data(n)
    payload_len = 1 + len(data)

    # Build frame without checksum first
    frame = bytearray()
    frame.append(FRAME_HEADER)
    frame.append(0x00)  # High byte of header (reserved)
    frame.append((payload_len >> 8) & 0xFF)  # Length high
    frame.append(payload_len & 0xFF)  # Length low
    frame.append(cmd)  # Command byte

    if data:
        frame.extend(data)

    # Calculate checksum (XOR of all bytes after header)
    checksum = 0
    for b in frame[2:]:
        checksum ^= b

    frame.append((checksum >> 8) & 0xFF)  # Checksum high
    frame.append(checksum & 0xFF)  # Checksum low

    return bytes(frame)

def send_command(cmd, data=None):
    """Send command to reader - don't wait for response, tag data comes via tcp_client_thread"""
    global client_socket

    try:
        with client_socket_lock:
            if client_socket is None:
                return {"success": False, "error": "Not connected to reader"}

            frame = build_command_frame(cmd, data)
            print(f"[CMD] Sending: {' '.join(f'{b:02X}' for b in frame)}")

            client_socket.send(frame)
            print(f"[CMD] Command {cmd:02X} sent successfully")
            return {"success": True, "response": "Command sent"}

    except Exception as e:
        print(f"[CMD] Error: {e}")
        return {"success": False, "error": str(e)}

def start_reading_epc():
    """Send command to start reading EPC tags"""
    global is_reading
    result = send_command(CMD_START_MULTI_EPC)
    if result.get("success"):
        is_reading = True
        print("[CMD] Start reading EPC")
    return result

def stop_reading_epc():
    """Send command to stop reading EPC tags"""
    global is_reading
    result = send_command(CMD_STOP_MULTI_EPC)
    if result.get("success"):
        is_reading = False
        print("[CMD] Stop reading EPC")
    return result

def tcp_client_thread():
    global is_connected, client_socket, is_reading

    while True:
        try:
            with client_socket_lock:
                if client_socket:
                    try:
                        client_socket.close()
                    except:
                        pass
                    client_socket = None

            print(f"[TCP] Connecting to reader {READER_HOST}:{READER_PORT}...")
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.settimeout(None)  # No timeout - blocking recv
            sock.connect((READER_HOST, READER_PORT))

            with client_socket_lock:
                client_socket = sock

            is_connected = True
            print(f"[TCP] Connected to reader")

            buffer = ""
            while True:
                try:
                    data = client_socket.recv(4096)
                    if not data:
                        print("[TCP] Connection closed by reader")
                        break

                    buffer += data.decode('utf-8', errors='ignore')

                    # Handle both \r\n and \n line endings
                    while '\r\n' in buffer or '\n' in buffer:
                        # Prefer \r\n splitting
                        if '\r\n' in buffer:
                            lines = buffer.split('\r\n')
                        else:
                            lines = buffer.split('\n')

                        for i in range(len(lines) - 1):
                            line = lines[i].strip()
                            if line:
                                tag = parse_epc_data(line)
                                if tag:
                                    with tag_list_lock:
                                        tag_list.insert(0, tag.to_dict())
                                        if len(tag_list) > MAX_TAGS:
                                            del tag_list[MAX_TAGS:]
                                    # socketio 已停用,前端用 /tags 轮询拉最新数据
                                    print(f"[DATA] EPC: {tag.epc}, TID: {tag.tid}, Ant: {tag.ant_id}")

                        buffer = lines[-1] if lines[-1].strip() else ""

                except socket.timeout:
                    continue
                except Exception as e:
                    print(f"[TCP] Receive error: {e}")
                    break

        except Exception as e:
            print(f"[TCP] Connection error: {e}")
            is_connected = False

        if not is_connected:
            print("[TCP] Retrying in 5 seconds...")
            time.sleep(5)

        is_connected = False
        is_reading = False

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/status')
def get_status():
    return jsonify({'connected': is_connected, 'reading': is_reading})

@app.route('/tags')
def get_tags():
    with tag_list_lock:
        return jsonify(tag_list)

@app.route('/command/start_read', methods=['POST'])
def cmd_start_read():
    """Start reading EPC tags"""
    result = start_reading_epc()
    return jsonify(result)

@app.route('/command/stop_read', methods=['POST'])
def cmd_stop_read():
    """Stop reading EPC tags"""
    result = stop_reading_epc()
    return jsonify(result)

@app.route('/command/reading_state', methods=['GET'])
def cmd_reading_state():
    """Get current reading state"""
    return jsonify({'reading': is_reading})

# ===== 旧的 SocketIO 事件处理器(已停用,改用 REST 轮询) =====
# 这些 handler 保留占位,如果以后要重新启用 SocketIO 只需取消注释
# @socketio.on('connect')
# def handle_connect(): ...
# @socketio.on('disconnect') ...
# @socketio.on('start_read') ...
# @socketio.on('stop_read') ...

# ============================================================
#  WMS 桥接 REST API(供前端选择出库单 / 触发扫码 / 拉扫码状态)
# ============================================================
try:
    import wms_bridge
    HAS_BRIDGE = True
except ImportError:
    HAS_BRIDGE = False

@app.route('/api/wms/draft-orders')
def api_wms_draft_orders():
    """返回 DRAFT 出库单列表,前端下拉选单用"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    keyword = request.args.get('keyword', '', type=str)
    orders = wms_bridge.load_draft_orders(keyword=keyword)
    return jsonify({'code': 0, 'data': orders})

@app.route('/api/wms/select-order', methods=['POST'])
def api_wms_select_order():
    """Web UI 选定一个 DRAFT 出库单(后续扫码都送这个单)"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    body = request.get_json(silent=True) or {}
    order_id = body.get('orderId')
    ok = wms_bridge.select_order(order_id)
    if not ok:
        return jsonify({'code': -1, 'message': '选择失败'})
    return jsonify({'code': 0, 'data': wms_bridge.get_state_snapshot()})

@app.route('/api/wms/state')
def api_wms_state():
    """Web UI 拉当前桥接状态(选中的单 / 已扫的 EPC 列表)"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    return jsonify({'code': 0, 'data': wms_bridge.get_state_snapshot()})

@app.route('/api/wms/login', methods=['POST'])
def api_wms_login():
    """重新登录(密码改了 / token 过期)"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    ok = wms_bridge.login()
    return jsonify({'code': 0 if ok else -1, 'data': {'loggedIn': ok}})

@app.route('/api/wms/reader-info')
def api_wms_reader_info():
    return jsonify({
        'code': 0,
        'data': {
            'host': READER_HOST,
            'port': READER_PORT,
            'connected': is_connected,
            'wms_url': WMS_BASE_URL,
        }
    })

@app.route('/api/wms/scan', methods=['POST'])
def api_wms_scan():
    """UHF 终端手动扫码入口(无读卡器时也能演示)。

    body: {"epc": "24 hex"}
    走和读卡器一样的路径:bridge.scan_epc → /orders/{id}/scan-epc
    """
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    body = request.get_json(silent=True) or {}
    epc = (body.get('epc') or '').strip().upper()
    if not epc:
        return jsonify({'code': -1, 'message': 'EPC 不能为空'})
    with wms_bridge.state.lock:
        oid = wms_bridge.state.selected_order_id
    if not oid:
        return jsonify({'code': -1, 'message': '请先选择出库单'})
    result = wms_bridge.scan_epc(oid, epc)
    return jsonify({'code': 0, 'data': result})

def main():
    thread = threading.Thread(target=tcp_client_thread, daemon=True)
    thread.start()

    # 启动 wms_bridge 桥接
    if HAS_BRIDGE:
        try:
            wms_bridge.main()
        except Exception as e:
            print(f"[MAIN] wms_bridge 启动失败: {e}")

    print("=" * 50)
    print("RFID Reader Demo Server (UHF-Rader + WMS Bridge)")
    print(f"Reader TCP: {READER_HOST}:{READER_PORT}")
    print(f"WMS:       {WMS_BASE_URL}")
    print(f"Web:       http://0.0.0.0:5000")
    print("=" * 50)

    # 直接用 Werkzeug threaded=True 跑(多线程并发处理请求,避免被
    # 后台 TCP/bridge 轮询线程的 GIL 竞争卡死)
    app.run(host='0.0.0.0', port=5000, debug=False,
            threaded=True, use_reloader=False)

if __name__ == '__main__':
    main()

