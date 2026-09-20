#!/usr/bin/env python
# -*- coding: utf-8 -*-

import os
import socket
import secrets
import threading
import time
from functools import wraps
from flask import Flask, render_template, jsonify, request
from datetime import datetime
import json
import struct

app = Flask(__name__)
# Flask-SocketIO + threading 模式在 demo 场景下会卡住 GIL,改用纯 Flask +
# 前端 JS 轮询的方式(2 秒拉一次 /api/wms/state)。生产可用 eventlet/gevent。
socketio = None  # 保留符号占位,wms_bridge 仍可能引用,但实际不触发 emit

# ====== CORS:允许 UHF 前端(localhost:5001)调 WMS 通过 bridge 代理 ======
# 演示场景给 *;生产应该限制 origin 白名单。
@app.after_request
def _cors(resp):
    resp.headers['Access-Control-Allow-Origin']  = '*'
    resp.headers['Access-Control-Allow-Methods'] = 'GET, POST, PUT, DELETE, OPTIONS'
    resp.headers['Access-Control-Allow-Headers'] = 'Content-Type, Authorization'
    return resp

@app.route('/<path:_any>', methods=['OPTIONS'])
def _preflight(_any):
    return ('', 204)

# ====== 本地会话(防跨站 CSRF / 任意网页直连)======
# 进程启动时生成一次性 token,以 SameSite=Strict + HttpOnly cookie 下发。
# 除 /api/wms/session 外,所有 /api/wms/* 与 /command/* 都要求该 cookie:
#   第三方网页发起的跨站请求不会携带 SameSite=Strict cookie → 403,
#   浏览器同源页面(xhr/fetch)自动携带 cookie → 正常放行。
SESSION_COOKIE = 'uhf_session'
_SESSION_TOKEN = secrets.token_urlsafe(32)

def _session_ok():
    c = request.cookies.get(SESSION_COOKIE)
    return c is not None and secrets.compare_digest(c, _SESSION_TOKEN)

def session_required(fn):
    @wraps(fn)
    def wrapper(*args, **kwargs):
        if not _session_ok():
            return jsonify({'code': -1, 'message': '未授权: 请从终端页面操作'}), 403
        return fn(*args, **kwargs)
    return wrapper

@app.route('/api/wms/session')
def api_wms_session():
    resp = jsonify({'code': 0, 'data': {'ok': True}})
    resp.set_cookie(SESSION_COOKIE, _SESSION_TOKEN, httponly=True,
                    samesite='Strict', path='/')
    return resp

# 读卡器 TCP 目标(从环境读,docker-compose 已注入)
READER_HOST = os.getenv("READER_HOST", "192.168.1.209")
READER_PORT = int(os.getenv("READER_PORT", "5000"))

# WMS 后端地址(容器内是 http://backend:8080)
WMS_BASE_URL = os.getenv("WMS_BASE_URL", "http://127.0.0.1:8080")
# 是否由外部(env/.env)显式指定了 WMS 地址。
#   - docker-compose 模式: True(WMS_BASE_URL=http://backend:8080),
#     此时浏览器填的 ?wms= 只是 UI 展示,实际请求一律走 env 地址
#     (容器内 localhost / 宿主机 IP 都不可达,填了必连不上)。
#   - 独立部署(直接 python app.py,未配 env): False,
#     运行时 ?wms= 才用来指向远程 WMS。
WMS_BASE_URL_CONFIGURED = bool(os.getenv("WMS_BASE_URL"))

# ===== 读卡器协议(0xBB 帧头 + SUM 校验 + 9 字节固定帧尾)=====
# 与该读卡器一致(型号:UHF+2.4G 双频读写器 / UHFReader.dll v2.1.1),
# 已用现场设备实测通过;协议细节对照 web-monitor 项目的 server.js。
#
#   帧结构: [0xBB][type 1B][len 1B][data N][checksum 1B][trailer 9B]
#   校验和: SUM(type + len + data) & 0xFF   (不含 0xBB 头,也不含帧尾)
#   帧尾  : 固定 9 字节 0D 0A 7E 7E 08 58 00 69 39
#
#   下行命令: 0x17 开始盘存(data=[0x00]) / 0x18 停止盘存
#   上行帧  : 0x97|0x96 标签数据 / 0x98 停止应答 / 0x40 心跳 / 0x3A 初始化
#
# ⚠️ 该读卡器把 0x17 当作"跑一轮盘存":发完标签帧后会发一个 5 字节的
#    0x97(payload[0]==0x00)作为"本轮结束"标记,然后回到 idle。要连续读,
#    必须每轮结束补发 0x18 → 200ms → 0x17(只重发 0x17 会被忽略)。
FRAME_HEADER = 0xBB
FRAME_TRAILER = bytes([0x0D, 0x0A, 0x7E, 0x7E, 0x08, 0x58, 0x00, 0x69, 0x39])

CMD_START_MULTI_EPC = 0x17
CMD_STOP_MULTI_EPC  = 0x18

TYPE_TAG_DATA       = 0x97
TYPE_TAG_DATA_ALT   = 0x96
TYPE_STOP_ACK       = 0x98
TYPE_HEARTBEAT      = 0x40
TYPE_INIT           = 0x3A

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

def calc_checksum(buf):
    """SUM(buf[1..]) & 0xFF —— 跳过 0xBB 帧头,对 type+len+data 求和。"""
    return sum(buf[1:]) & 0xFF

def build_command_frame(cmd, data=None):
    """按 0xBB 协议构造命令帧: [BB][cmd][len][data][SUM][trailer 9B]。"""
    data = bytes(data or [])
    pre = bytes([FRAME_HEADER, cmd, len(data)]) + data
    return pre + bytes([calc_checksum(pre)]) + FRAME_TRAILER

def _parse_tag_entries(payload):
    """从 0x97/0x96 载荷里解出标签。载荷布局(与 web-monitor server.js 一致):

        34B: [DevID 2B LE][EPC1 12B][EPC2 12B] ...  RSSI@27, Count@28(2B LE)
        35B: [DevID 2B LE][Ant 1B][EPC1 12B][Ant 1B][EPC2 12B] ... RSSI@27, Count@28
        其他: [DevID 2B LE][EPC 12B]

    返回 [(epc_hex, ant_id, rssi, count, dev_id)]
    """
    n = len(payload)
    if n < 14:
        return []
    dev_id = payload[0] | (payload[1] << 8)
    # 双天线:一帧里的两组 EPC 就是「1 号天线」「2 号天线」各自读到的标签。
    # 现场实测两槽位的 EPC 集合互不相交(槽1 全是 0000000035003… 的标签,
    # 槽2 全是 E2803821… 的标签),未读到的槽位是全 0(下面过滤掉)。
    # 不要再从 payload[2] 取"天线号" —— 那是 EPC 的第一个字节。
    if n == 34:
        entries = [(payload[2:14], 1), (payload[14:26], 2)]
    elif n == 35:
        entries = [(payload[3:15], 1), (payload[16:28], 2)]
    else:
        entries = [(payload[2:14], 1)]

    out = []
    for epc_bin, ant_slot in entries:
        if len(epc_bin) < 12:
            continue
        epc = epc_bin.hex().upper()
        # 空槽位/无效码过滤:全 0、全 F、0 字节过多都丢弃
        if set(epc) == {'0'} or set(epc) == {'F'}:
            continue
        if epc_bin.count(0) > 8:
            continue
        ant = str(ant_slot)
        rssi = -(payload[27] & 0x7F) if 27 < n else 0
        count = 1
        if 28 + 1 < n:
            count = payload[28] | (payload[29] << 8) or 1
        out.append((epc, ant, rssi, count, dev_id))
    return out

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

# ---- 连续盘存:该读卡器一次 0x17 只跑一轮,需要每轮结束补一次 0x18→0x17 ----
_last_cycle_ts = 0.0
_cycle_lock = threading.Lock()
CYCLE_RESTART_COOLDOWN = 1.0   # 秒,防止结束标记抖动导致风暴
CYCLE_MAX_DURATION     = 5.0   # 秒,兜底:超过这么久没结束标记就强制重启一轮

def _restart_inventory_cycle(source):
    """一轮盘存结束后重启下一轮:0x18 停止 → 200ms → 0x17 开始。

    设备在每轮结束后回到 idle,单独重发 0x17 会被忽略,必须先 0x18。
    """
    global _last_cycle_ts
    if not is_reading:
        return
    with _cycle_lock:
        now = time.time()
        if now - _last_cycle_ts < CYCLE_RESTART_COOLDOWN:
            return
        _last_cycle_ts = now
    print(f"[CYCLE] restart ({source})")
    send_command(CMD_STOP_MULTI_EPC)

    def _start_next():
        if is_reading:
            send_command(CMD_START_MULTI_EPC, [0x00])

    threading.Timer(0.2, _start_next).start()

def _reading_safety_net():
    """兜底线程:万一丢了一轮结束标记,超过 CYCLE_MAX_DURATION 强制重启一轮。"""
    while True:
        time.sleep(1.0)
        if is_reading and (time.time() - _last_cycle_ts) > CYCLE_MAX_DURATION:
            _restart_inventory_cycle('safety')

def start_reading_epc():
    """开始盘存:发 0x17(data=[0x00]),并记录本轮的起始时间。"""
    global is_reading, _last_cycle_ts
    result = send_command(CMD_START_MULTI_EPC, [0x00])
    if result.get("success"):
        is_reading = True
        _last_cycle_ts = time.time()
        print("[CMD] Start reading EPC")
    return result

def stop_reading_epc():
    """停止盘存:发 0x18。"""
    global is_reading
    result = send_command(CMD_STOP_MULTI_EPC)
    if result.get("success"):
        is_reading = False
        print("[CMD] Stop reading EPC")
    return result

def _consume_frames(buf):
    """从二进制缓冲里按 0xBB 帧结构切帧并处理,返回未消费的剩余字节。

    frame_len = 1(头) + 1(类型) + 1(长度) + len(数据) + 1(校验) + 9(帧尾)
    """
    while True:
        i = buf.find(bytes([FRAME_HEADER]))
        if i < 0:
            return b""            # 没有帧头,丢弃(噪声/半帧)
        if i > 0:
            buf = buf[i:]         # 丢弃帧头之前的垃圾
        if len(buf) < 4:
            return buf            # 连长度字段都不够,等更多数据
        data_len = buf[2]
        frame_len = 1 + 1 + 1 + data_len + 1 + 9
        if len(buf) < frame_len:
            return buf            # 整帧还没收全,等下一次 recv
        frame = buf[:frame_len]
        buf = buf[frame_len:]
        try:
            _handle_frame(frame[1], frame[3:3 + data_len])
        except Exception as e:
            print(f"[FRAME] handle error: {e}")


def _handle_frame(ftype, payload):
    """处理一帧上行数据。"""
    if ftype in (TYPE_TAG_DATA, TYPE_TAG_DATA_ALT):
        # 5 字节且 payload[0]==0x00 = 本轮盘存结束标记 -> 重启下一轮
        if len(payload) == 5 and payload[0] == 0x00:
            _restart_inventory_cycle('marker')
            return
        ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        for epc, ant, rssi, count, dev_id in _parse_tag_entries(payload):
            tag = TagInfo(epc, "", ant, str(rssi), ts)
            with tag_list_lock:
                tag_list.insert(0, tag.to_dict())
                if len(tag_list) > MAX_TAGS:
                    del tag_list[MAX_TAGS:]
            print(f"[DATA] EPC: {epc}, RSSI: {rssi}, Count: {count}, DevID: {dev_id}")
    elif ftype in (TYPE_HEARTBEAT, TYPE_INIT, TYPE_STOP_ACK):
        pass                       # 心跳/初始化/停止应答:正常,不处理
    else:
        print(f"[FRAME] unknown type=0x{ftype:02X} payload={payload.hex()}")


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

            buffer = b""
            raw_dumped = 0
            while True:
                try:
                    data = client_socket.recv(4096)
                    if not data:
                        print("[TCP] Connection closed by reader")
                        break

                    # 调试:打印原始字节(每个连接前 40 个 chunk),核对现场协议用
                    if raw_dumped < 40:
                        raw_dumped += 1
                        print(f"[RAW] {data.hex()}")

                    # 二进制处理:按 0xBB 帧结构切帧,绝不能 decode 成文本再解析
                    buffer = _consume_frames(buffer + data)

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
@session_required
def cmd_start_read():
    """Start reading EPC tags"""
    result = start_reading_epc()
    return jsonify(result)

@app.route('/command/stop_read', methods=['POST'])
@session_required
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
@session_required
def api_wms_draft_orders():
    """返回 DRAFT 出库单列表,前端下拉选单用"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    keyword = request.args.get('keyword', '', type=str)
    orders = wms_bridge.load_draft_orders(keyword=keyword)
    return jsonify({'code': 0, 'data': orders})

@app.route('/api/wms/select-order', methods=['POST'])
@session_required
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
@session_required
def api_wms_state():
    """Web UI 拉当前桥接状态(选中的单 / 已扫的 EPC 列表)"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    return jsonify({'code': 0, 'data': wms_bridge.get_state_snapshot()})

@app.route('/api/wms/login', methods=['POST'])
@session_required
def api_wms_login():
    """UHF 前端登录入口:接收用户名/密码 → bridge 代为调 WMS /api/auth/login,
    拿到 token 存到 wms_bridge.state,后续所有 /api/wms/* 都用这个 token 调 WMS。

    query: ?wms=http://192.168.1.100:9080  登录时覆盖 WMS URL(独立部署用;
           填 nginx 入口 9080,不是后端直连的 9090 —— 那个端口只绑本机回环)
    之所以这样设计:
      - 前端只需要跟同源 5001 通信,避开 CORS
      - token 不必下发给浏览器,只存在 bridge 进程里
      - 真正'全用户可登录'在这里落地
    """
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    body = request.get_json(silent=True) or {}
    username = (body.get('username') or '').strip()
    password = body.get('password') or ''
    if not username or not password:
        return jsonify({'code': -1, 'message': '用户名或密码不能为空'}), 400
    # 可选:浏览器把填的 WMS URL 传过来(独立部署用)。是否真的影响请求
    # 由 _wms_base() 决定:env 已配 WMS_BASE_URL(docker-compose)时被忽略,
    # 一律走 http://backend:8080;仅未配 env 的独立部署才用这个运行时覆盖。
    wms_override = (request.args.get('wms') or '').strip()
    if wms_override:
        with wms_bridge.state.lock:
            wms_bridge.state.wms_url = wms_override.rstrip('/')
    # 调 WMS
    r = wms_bridge._http_post('/api/auth/login',
                             {'username': username, 'password': password})
    if r.get('code') == 0 and r.get('data', {}).get('token'):
        d = r['data']
        with wms_bridge.state.lock:
            wms_bridge.state.token = d['token']
            wms_bridge.state.user = username
            wms_bridge.state.role = d.get('role', '')
        return jsonify({
            'code': 0,
            'data': {
                'username': username,
                'role': d.get('role', ''),
                'loggedIn': True,
                'wmsUrl': wms_bridge._wms_base() if hasattr(wms_bridge, '_wms_base') else '',
            }
        })
    return jsonify({'code': r.get('code', -1),
                    'message': r.get('message', '登录失败')}), 401

@app.route('/api/wms/reader-info')
@session_required
def api_wms_reader_info():
    return jsonify({
        'code': 0,
        'data': {
            'host': READER_HOST,
            'port': READER_PORT,
            'connected': is_connected,
            # 前端据此同步「开始/停止」按钮的可用态,避免页面状态与设备脱节
            # (按钮 disabled 但设备其实已停 → 点了没反应、日志也无请求)
            'reading': is_reading,
            'wms_url': WMS_BASE_URL,
        }
    })

@app.route('/api/wms/scan', methods=['POST'])
@session_required
def api_wms_scan():
    """UHF 终端手动扫码入口(无读卡器时也能演示)。

    body: {"epc": "24 hex", "orderId": 8}  ← 前端传 orderId,避免依赖 bridge state
    走和读卡器一样的路径:bridge.scan_epc → /orders/{id}/scan-epc
    """
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    body = request.get_json(silent=True) or {}
    epc = (body.get('epc') or '').strip().upper()
    if not epc:
        return jsonify({'code': -1, 'message': 'EPC 不能为空'}), 400
    # 安全:入站 EPC 必须严格 24 位 hex(与后端一致)。非 hex 直接拒绝且不写入
    # scanned_cache,避免任意字符串被回显到前端 innerHTML(存储型 XSS)。
    if len(epc) != 24 or any(c not in '0123456789ABCDEF' for c in epc):
        return jsonify({'code': -1, 'message': f'EPC 无效(需 24 位十六进制)'}), 400
    # 前端可显式传 orderId,否则用 bridge 当前选中
    raw_oid = body.get('orderId')
    if raw_oid is None:
        with wms_bridge.state.lock:
            raw_oid = wms_bridge.state.selected_order_id
    try:
        oid = int(raw_oid)
    except (TypeError, ValueError):
        return jsonify({'code': -1, 'message': 'orderId 无效'}), 400
    if not oid:
        return jsonify({'code': -1, 'message': '请先选择出库单'}), 400
    # 该 EPC 本单已命中过:直接回缓存的成功结果,不要再去后端撞"已扫过(本单)"
    # (循环读卡/手动重扫都不应把已确认的结果"重置")
    with wms_bridge.state.lock:
        prev = wms_bridge.state.scanned_cache.get(epc)
    if prev and prev.get('matched'):
        return jsonify({'code': 0, 'data': prev, 'cached': True})
    result = wms_bridge.scan_epc(oid, epc)
    # 与读卡器扫码泵(scan_pump_loop)一致:手动结果写入 scanned_cache,
    # 前端 /api/wms/state 轮询才不会把它当成"新数据已清空"覆盖掉。
    # cache_scan:有界(上限)+ 与当前选中单绑定,避免跨单/无限增长。
    if isinstance(result, dict):
        result['_epc'] = epc
        result['_ts'] = datetime.now().strftime('%Y-%m-%d %H:%M:%S')
        wms_bridge.cache_scan(epc, result, order_id=oid)
    return jsonify({'code': 0, 'data': result})

# ====== 给前端用的 WMS 业务代理(避免跨域) ======
def _bridge_auth_headers():
    """从 bridge state 拿当前用户的 token,proxy 请求透传。"""
    if not HAS_BRIDGE:
        return None
    return wms_bridge._auth_headers() or None

@app.route('/api/wms/orders/<int:oid>/expect', methods=['GET'])
@session_required
def api_wms_order_expect(oid):
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 未加载'}), 500
    return jsonify(wms_bridge._http_get(f'/api/outbound/orders/{oid}/expect',
                                        headers=_bridge_auth_headers() or {}))

@app.route('/api/wms/orders/<int:oid>/commit', methods=['POST'])
@session_required
def api_wms_order_commit(oid):
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 未加载'}), 500
    return jsonify(wms_bridge._http_post(f'/api/outbound/orders/{oid}/commit', {},
                                          headers=_bridge_auth_headers() or {}))

@app.route('/api/wms/orders/<int:oid>/cancel', methods=['POST'])
@session_required
def api_wms_order_cancel(oid):
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 未加载'}), 500
    return jsonify(wms_bridge._http_post(f'/api/outbound/orders/{oid}/cancel', {},
                                          headers=_bridge_auth_headers() or {}))

@app.route('/api/wms/orders', methods=['GET'])
@session_required
def api_wms_orders_list():
    """拉 DRAFT 出库单(前端用)"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 未加载'}), 500
    keyword = request.args.get('keyword', '', type=str)
    # 走 bridge 已带 token 的 load_draft_orders
    return jsonify({'code': 0, 'data': wms_bridge.load_draft_orders(keyword=keyword)})

@app.route('/api/wms/logout', methods=['POST'])
@session_required
def api_wms_logout():
    """登出:清空 bridge 持有的 WMS token 与当前作业状态。"""
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    wms_bridge.logout()
    return jsonify({'code': 0, 'data': {'loggedIn': False}})

@app.route('/api/wms/scan-clear', methods=['POST'])
@session_required
def api_wms_scan_clear():
    """清空当前扫码记录,不清选单。

    两件事都要做,否则「清空」是假的:
      1) 清 bridge 的 scanned_cache(界面立即空)
      2) 调后端 reset-scans 删掉本单 SCAN 日志 —— 不然同一 EPC 再扫
         仍被去重挡回"EPC 已扫过(本单)",错扫后无法重扫。
    """
    if not HAS_BRIDGE:
        return jsonify({'code': -1, 'message': 'wms_bridge 模块未加载'}), 500
    with wms_bridge.state.lock:
        oid = wms_bridge.state.selected_order_id
    deleted = wms_bridge.reset_scans(oid) if oid else 0
    wms_bridge.clear_scans()
    snap = wms_bridge.get_state_snapshot()
    return jsonify({'code': 0, 'data': snap, 'deleted': deleted})

def main():
    thread = threading.Thread(target=tcp_client_thread, daemon=True)
    thread.start()

    # 连续盘存兜底:丢了"本轮结束"标记时强制重启一轮
    threading.Thread(target=_reading_safety_net, daemon=True).start()

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

