#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
UHF-Rader ↔ WMS 桥接(直连 REST 模式,取代旧版 MQTT)。

流程:
  1. 启动 → POST /api/auth/login 拿 token(单次,token 后续复用)
  2. 启动 → 加载 DRAFT 出库单列表
  3. 操作员在 Web UI 选定一个 DRAFT 出库单
  4. Web 调 GET /api/outbound/orders/{id}/expect 拉期望扫码清单
  5. 操作员点"开始扫码" → app.py 启动读卡器,扫到 EPC 写入 tag_list
  6. wms_bridge 后台线程持续监听 tag_list 新增,把每个 EPC POST /api/outbound/orders/{id}/scan-epc
  7. 后端返回 {matched, reason, sku, scannedQty},UI 用颜色 + 音反馈

⚠️  本文件复用 app.py 的 tag_list / tag_list_lock / is_connected。
   必须和 app.py 在同目录运行。
"""

import os
import json
import logging
import threading
import time
from datetime import datetime
from typing import Dict, List, Optional

import sys
import urllib.request
import urllib.error

# 复用 app.py 的配置常量(值型,import 一份无妨)
try:
    from app import READER_HOST, READER_PORT
except ImportError:
    print("[WMS-Bridge] FATAL: 必须和 app.py 在同目录运行(共享 tag_list 等内部对象)")
    raise


def _live_app():
    """返回「真正持有运行期状态」的 app 模块。

    ⚠️ 两个坑,都踩过:
    1) `python app.py` 启动时模块名是 __main__;而本文件里 `import app`
       会让 Python 把 app.py **再执行一遍**,产生一个独立的 `app` 模块,
       它有自己的空 tag_list / is_reading=False。扫码泵若读那份,就永远
       看不到读卡线程写入的标签(表现为:/tags 有数据、后端却收不到
       scan-epc 请求)。
    2) is_reading / is_connected 是模块级标量,app.py 用 `global x; x=...`
       重新绑定,`from app import is_reading` 会固化旧值(永远 False)。

    所以运行期统一从这里取模块,再取属性。
    """
    m = sys.modules.get('__main__')
    if m is not None and hasattr(m, 'tag_list') and hasattr(m, 'is_reading'):
        return m                      # app.py 作为脚本运行(生产/容器路径)
    return sys.modules.get('app')      # app.py 被当模块 import


def _wms_base():
    """WMS URL 运行时可被登录接口覆盖。

    docker-compose 模式(env WMS_BASE_URL 已配,如 http://backend:8080):
      容器内只能访问 backend 服务名,浏览器填的 localhost / 宿主机 IP 都不可达,
      因此一律返回 env 地址,忽略 state.wms_url(它只保留给 UI 展示)。
    独立部署(未配 env WMS_BASE_URL): 返回登录时浏览器传的 state.wms_url,
      用于指向远程 WMS。
    """
    from app import WMS_BASE_URL as _env, WMS_BASE_URL_CONFIGURED
    if WMS_BASE_URL_CONFIGURED:
        return _env
    return state.wms_url or _env

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(name)s: %(message)s'
)
log = logging.getLogger("wms-bridge")

# ==================== 配置 ====================
WMS_USERNAME = os.getenv("WMS_USERNAME", "admin")
WMS_PASSWORD = os.getenv("WMS_PASSWORD", "admin123")
DEVICE_CODE  = os.getenv("DEFAULT_DEVICE_CODE", "MOCK-UHF-001")
HTTP_TIMEOUT  = 5  # 秒
MAX_SCANNED   = 200  # scanned_cache 上限(防止长跑内存无限增长)

# ==================== 状态 ====================
class BridgeState:
    def __init__(self):
        self.token: Optional[str] = None
        self.user: Optional[str] = None     # 当前登录的 WMS 用户名
        self.role: Optional[str] = None     # ADMIN / OPERATOR
        self.wms_url: Optional[str] = None  # 登录时覆盖 env 默认 URL
        self.login_lock = threading.Lock()
        # 当前选定的 DRAFT 出库单
        self.selected_order_id: Optional[int] = None
        self.selected_order_no: Optional[str] = None
        # 期望扫码清单
        self.expected_epcs: List[dict] = []
        # 已扫过的 EPC(本地 cache,跨刷新)
        self.scanned_cache: Dict[str, dict] = {}  # epc -> ScanResult
        # 本单已"命中确认"的 EPC(只增不减,不受活动流水窗口/容量上限影响)
        # 循环读卡时活动流水会被未匹配标签刷屏,若只靠 scanned_cache 的
        # 最近 N 条来算 ✓,已匹配的记录会被挤出去,进度看起来就"被重置"了。
        self.matched_epcs: Dict[str, dict] = {}   # epc -> ScanResult(matched=True)
        # 锁
        self.lock = threading.Lock()

state = BridgeState()

# ==================== HTTP 客户端 ====================
def _http_post(path, body=None, headers=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        _wms_base() + path,
        data=data,
        method="POST",
        headers={"Content-Type": "application/json", **(headers or {})}
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", errors="ignore")
        log.error("[HTTP] %s %s -> %d body=%s", "POST", path, e.code, body[:200])
        return {"code": e.code, "message": f"HTTP {e.code}", "data": None}
    except Exception as e:
        log.error("[HTTP] %s %s -> error: %s", "POST", path, e)
        return {"code": -1, "message": str(e), "data": None}

def _http_get(path, headers=None):
    req = urllib.request.Request(
        _wms_base() + path,
        method="GET",
        headers={**(headers or {})}
    )
    try:
        with urllib.request.urlopen(req, timeout=HTTP_TIMEOUT) as r:
            return json.loads(r.read().decode("utf-8"))
    except Exception as e:
        log.error("[HTTP] GET %s -> error: %s", path, e)
        return {"code": -1, "message": str(e), "data": None}

def _auth_headers():
    with state.lock:
        if not state.token:
            return None
        return {"Authorization": "Bearer " + state.token}

# ==================== 登录 ====================
# 被限流(429)后的冷却秒数。
#
# 后端连续失败会锁定(默认 5 分钟),这段时间继续重试毫无意义;更麻烦的是,
# 它会把"人在 UHF 登录页手输正确口令"也一起顶掉 —— 那个页面的登录请求是**本容器**
# 代发的,来源 IP 与后台自动登录完全相同,于是两者共用后端的同一个限流桶
# ((用户名, IP) 那一把钥匙)。所以一收到 429 就闭嘴等着,冷却期内一次 HTTP 都不发。
#
# 取 60 秒(比后端 5 分钟的锁短):既不踩着锁刷,又能在凭据改对后最多 60 秒内自动恢复。
LOGIN_COOLDOWN_SEC = 60
_login_cooldown_until = 0.0


def login():
    """登录 WMS 后端,拿 token。失败最多重试 3 次,间隔 2 秒;
    一旦被限流(429)立刻停止本轮并进入冷却。"""
    global _login_cooldown_until
    with state.login_lock:
        if time.time() < _login_cooldown_until:
            # 冷却中:后端此刻必然仍回 429,发了也是白发
            return False
        for attempt in range(1, 4):
            r = _http_post("/api/auth/login",
                           {"username": WMS_USERNAME, "password": WMS_PASSWORD})
            if r.get("code") == 0 and r.get("data", {}).get("token"):
                state.token = r["data"]["token"]
                _login_cooldown_until = 0.0      # 成功即解除冷却
                log.info("[LOGIN] success, role=%s", r["data"].get("role"))
                return True
            state.token = None
            # 429 是 _http_post 从 HTTPError 分支带回来的(code = HTTP 状态码),
            # 直接看数字即可 —— 不去猜「请 N 分钟后再试」这类提示文案,
            # 与仓库里"状态判断不依赖消息文本"的约定一致。
            if r.get("code") == 429:
                _login_cooldown_until = time.time() + LOGIN_COOLDOWN_SEC
                log.warning("[LOGIN] 被后端限流(429),%d 秒内不再尝试登录", LOGIN_COOLDOWN_SEC)
                return False
            log.warning("[LOGIN] attempt %d/3 failed: %s",
                        attempt, r.get("message") or r.get("code"))
            if attempt < 3:
                time.sleep(2)
        log.error("[LOGIN] all 3 attempts failed")
        return False

# ==================== 业务 API ====================
def load_draft_orders(keyword=""):
    """拉 DRAFT 出库单列表(Web UI 启动时调用)"""
    hdrs = _auth_headers()
    if not hdrs:
        if not login():
            return []
    path = f"/api/outbound/orders?status=DRAFT&pageSize=50"
    if keyword:
        path += f"&keyword={urllib.parse.quote(keyword)}"
    r = _http_get(path, headers=hdrs or {})
    if r.get("code") == 0:
        return r.get("data", {}).get("list", [])
    return []

def load_expected_epcs(order_id):
    """拉指定出库单的期望扫码清单"""
    hdrs = _auth_headers()
    if not hdrs:
        if not login():
            return []
    r = _http_get(f"/api/outbound/orders/{order_id}/expect", headers=hdrs or {})
    if r.get("code") == 0:
        return r.get("data", []) or []
    return []

def scan_epc(order_id, epc):
    """调后端 scan-epc 接口。EPC 必须是 24 位 hex(12 字节 = 96 bit UHF Gen2)。

    createdBy 用当前登录用户(全用户支持);没登录时回退到 env 默认账号。
    """
    if not epc:
        return {"matched": False, "reason": "EPC 为空"}
    e = epc.strip().upper()
    if len(e) != 24:
        return {"matched": False, "reason": f"EPC 长度 {len(e)} 不是 24 hex"}
    # 全 hex 字符
    for c in e:
        if not (c.isdigit() or c in "ABCDEF"):
            return {"matched": False, "reason": f"EPC 含非 hex 字符: {e}"}
    hdrs = _auth_headers()
    if not hdrs:
        if not login():
            return {"matched": False, "reason": "登录失败"}
    r = _http_post(f"/api/outbound/orders/{order_id}/scan-epc",
                   {"epc": e, "createdBy": (state.user or WMS_USERNAME)},
                   headers=hdrs or {})
    if r.get("code") == 0:
        return r.get("data", {"matched": False, "reason": "返回为空"})
    return {"matched": False, "reason": r.get("message", "后端错误")}

def reset_scans(order_id):
    """撤销后端本单的全部扫码确认(清 SCAN 日志)。

    UHF 终端「清空」按钮用:否则只在界面上清掉,后端仍记着"已扫过",
    同一个 EPC 再也扫不进去。返回删除条数;失败返回 None。
    """
    if not order_id:
        return None
    hdrs = _auth_headers()
    if not hdrs:
        if not login():
            return None
    r = _http_post(f"/api/outbound/orders/{order_id}/reset-scans", {}, headers=hdrs or {})
    if r.get("code") == 0:
        return (r.get("data") or {}).get("deleted", 0)
    log.warning("[RESET-SCANS] order=%s failed: %s", order_id, r.get("message"))
    return None

def cache_scan(epc, result, order_id=None):
    """写入 scanned_cache(读卡器扫码泵与手动扫码共用)。

    - 有界:超过 MAX_SCANNED 丢最旧(key 依插入序,重写先删再插移到队尾,
      保证 get_state_snapshot 的 [-30:] 反映"最近"扫码)。
    - 订单绑定:order_id 与当前选中单不一致时丢弃,避免跨单竞态把旧单结果
      写进新单视图。
    """
    with state.lock:
        if order_id is not None and state.selected_order_id != order_id:
            return False
        prev = state.scanned_cache.get(epc)
        # 循环读卡:同一个 EPC 会被反复读到。只要**命中过一次**,就不能再被
        # 后续的"已扫过(本单)/不匹配"覆盖 —— 否则界面上的 ✓ 和"已扫 N/共 M"
        # 会被打回原形(用户反馈的"第二次扫描又重置")。
        if prev and prev.get("matched") and not result.get("matched"):
            return False
        if epc in state.scanned_cache:
            del state.scanned_cache[epc]
        state.scanned_cache[epc] = result
        while len(state.scanned_cache) > MAX_SCANNED:
            state.scanned_cache.pop(next(iter(state.scanned_cache)))
        if result.get("matched"):
            state.matched_epcs[epc] = result
    return True

# ==================== 后台:tag_list → WMS ====================
# 扫码泵的"已推送"去重集合。必须是可重置的模块级状态:
# 之前是 scan_pump_loop 的局部变量,导致「清空」和「切换出库单」之后
# 现场仍在读的同一批标签被永久跳过 —— 界面清空后再也扫不回来,只能重启
# 容器。现在由 reset_pump_seen() 在清空/换单时一并重置。
_PUMP_SEEN = {"last_seen": 0, "last_pushed": set()}

def reset_pump_seen():
    """清空扫码泵的去重集合,让现场标签可以重新推送。

    在「清空」和「切换出库单」时调用(否则同一批标签永远不会再出现)。
    """
    _PUMP_SEEN["last_seen"] = 0
    _PUMP_SEEN["last_pushed"].clear()

def scan_pump_loop():
    """监听 tag_list 新增,把每个新 EPC 推给 WMS。"""
    while True:
        try:
            last_seen = _PUMP_SEEN["last_seen"]
            last_pushed = _PUMP_SEEN["last_pushed"]
            # 每轮都取一次「有状态的那个模块」(见 _live_app 注释:__main__ vs app)
            appm = _live_app()
            if appm is None:
                time.sleep(0.3)
                continue
            with appm.tag_list_lock:
                current = list(appm.tag_list)
            with state.lock:
                cur_order_id = state.selected_order_id
            if cur_order_id and current:
                # 看是否有新 tag
                if len(current) > last_seen or current[0].get("epc") not in last_pushed:
                    # 推送每个新出现的 EPC
                    for item in current[:8]:
                        epc = (item.get("epc") or "").strip().upper()
                        if not epc or epc in last_pushed:
                            continue
                        # 运行期读取(见 _live_app 注释:from-import 的标量会固化旧值)
                        if not appm.is_reading:
                            continue
                        # 已经命中过本单的 EPC 不再重复上报:读卡器是循环读卡的,
                        # 同一标签会被反复读到;重复上报只会拿到"已扫过(本单)"的
                        # 拒绝结果,既浪费请求又可能把界面上的 ✓ 冲掉。
                        with state.lock:
                            cached = state.scanned_cache.get(epc)
                        if cached and cached.get("matched"):
                            last_pushed.add(epc)
                            continue
                        result = scan_epc(cur_order_id, epc)
                        result["_epc"] = epc
                        result["_ts"] = item.get("timestamp") or datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                        cache_scan(epc, result, order_id=cur_order_id)
                        last_pushed.add(epc)
                        # 推送到 Web UI
                        try:
                            from app import socketio
                            socketio.emit("wms_scan_result", result)
                        except Exception:
                            pass
                        if result.get("matched"):
                            log.info("[SCAN] ✓ %s sku=%s scannedQty=%d", epc, result.get("sku"), result.get("scannedQty"))
                        else:
                            log.warning("[SCAN] ✗ %s reason=%s", epc, result.get("reason"))
                    _PUMP_SEEN["last_seen"] = len(current)
        except Exception as e:
            log.exception("[SCAN-PUMP] error")
        time.sleep(0.3)

# ==================== Web UI 接入(供 app.py 调用) ====================
def select_order(order_id):
    """Web UI 选定出库单 → 拉期望清单 → 更新状态"""
    with state.lock:
        state.selected_order_id = order_id
        state.scanned_cache.clear()
        state.matched_epcs.clear()
    # 换单后现场同一批标签要能重新推给新单(否则被泵的去重集合挡掉)
    reset_pump_seen()
    if order_id is None:
        state.expected_epcs = []
        return True
    state.expected_epcs = load_expected_epcs(order_id)
    log.info("[SELECT] order_id=%d, expected=%d EPCs", order_id, len(state.expected_epcs))
    return True

def clear_scans():
    """清空已扫记录(前端「清空」按钮),不清选单、不影响期望清单。

    注意也要重置扫码泵的去重集合:否则现场仍在读的同一批标签会被泵
    「已推送过」跳过,清空后再也扫不回来(必须重启容器)。
    """
    with state.lock:
        state.scanned_cache.clear()
        state.matched_epcs.clear()
    reset_pump_seen()
    log.info("[CLEAR] scanned_cache cleared (pump de-dup reset)")

def logout():
    """登出:清空 WMS token / 当前用户 / 作业状态(选单 + 期望 + 已扫)。"""
    with state.lock:
        state.token = None
        state.user = None
        state.role = None
        state.wms_url = None
        state.selected_order_id = None
        state.selected_order_no = None
        state.expected_epcs = []
        state.scanned_cache.clear()
        state.matched_epcs.clear()
    log.info("[LOGOUT] token & order state cleared")

def get_state_snapshot():
    """Web UI 拉一次状态(出库单列表 + 选定 + 期望 + 已扫)"""
    with state.lock:
        return {
            "loggedIn": state.token is not None,
            "user": state.user,
            "role": state.role,
            "selectedOrderId": state.selected_order_id,
            "selectedOrderNo": state.selected_order_no,
            "expectedCount": len(state.expected_epcs),
            "scannedCount": len(state.scanned_cache),
            "scanned": list(state.scanned_cache.values())[-30:],  # 最近 30 条(活动流水)
            # 本单已命中的全部 EPC —— 界面用它算 ✓ 与"已扫 N/共 M",
            # 不受上面 30 条流水窗口的影响(循环读卡不会被冲掉进度)
            "matched": list(state.matched_epcs.values()),
            "matchedCount": len(state.matched_epcs),
            "deviceCode": DEVICE_CODE,
        }

# ==================== 派单轮询 ====================
def list_dispatched():
    """拉 WMS 后端派给本设备(或任何设备)的待认领单。"""
    hdrs = _auth_headers()
    if not hdrs:
        if not login():
            return []
    path = f"/api/outbound/dispatches?deviceCode={urllib.parse.quote(DEVICE_CODE)}&limit=20"
    r = _http_get(path, headers=hdrs or {})
    if r.get("code") == 0:
        return r.get("data", []) or []
    return []

def claim_dispatch(order_id):
    """认领一个派单(锁定到本设备)"""
    hdrs = _auth_headers()
    if not hdrs:
        if not login():
            return None
    path = f"/api/outbound/dispatches/{order_id}/claim?deviceCode={urllib.parse.quote(DEVICE_CODE)}"
    r = _http_post(path, headers=hdrs or {})
    if r.get("code") == 0:
        return r.get("data")
    log.warning("[CLAIM] failed: %s", r.get("message"))
    return None

def dispatch_poll_loop():
    """后台轮询: 拉取派给本设备的待认领单,认领并切换为当前选中单。

    ⚠️  关键:不要在 state.lock 内做网络 I/O,否则会把 /api/wms/state 等
        共享锁的端点卡住。
    """
    last_poll_ts = 0
    while True:
        try:
            need_poll = False
            with state.lock:
                if state.selected_order_id is None and time.time() - last_poll_ts >= 5:
                    need_poll = True
            if need_poll:
                with state.lock:
                    last_poll_ts = time.time()  # 先更新时间戳,避免反复轮询
                # 锁外做网络 I/O
                dispatched = list_dispatched()
                if dispatched:
                    first = dispatched[0]
                    oid = first.get("id")
                    if oid:
                        log.info("[DISPATCH] found %d pending, claiming #%s", len(dispatched), oid)
                        claimed = claim_dispatch(oid)
                        if claimed:
                            select_order(oid)
        except Exception as e:
            log.exception("[DISPATCH-POLL] error")
        time.sleep(2)

# ==================== 入口 ====================
def main():
    log.info("=" * 50)
    log.info("WMS Bridge (REST mode)")
    log.info("  WMS endpoint: %s", _wms_base())
    log.info("  Login as:     %s", WMS_USERNAME)
    log.info("  Device:       %s", DEVICE_CODE)
    log.info("=" * 50)

    # 1. 登录
    if not login():
        log.warning("[WMS-Bridge] 登录失败,启动后端不可用,可重试")

    # 2. 启动扫码泵
    t = threading.Thread(target=scan_pump_loop, daemon=True)
    t.start()
    log.info("[WMS-Bridge] scan pump started")

    # 3. 启动派单轮询(WMS 前台下发的单子会被自动认领并切换为当前单)
    t2 = threading.Thread(target=dispatch_poll_loop, daemon=True)
    t2.start()
    log.info("[WMS-Bridge] dispatch poll started (device=%s)", DEVICE_CODE)

if __name__ == "__main__":
    main()
