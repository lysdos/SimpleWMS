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

import urllib.request
import urllib.error

# 复用 app.py 的状态
try:
    from app import (
        READER_HOST, READER_PORT, WMS_BASE_URL,
        tag_list, tag_list_lock, is_connected, is_reading
    )
except ImportError:
    print("[WMS-Bridge] FATAL: 必须和 app.py 在同目录运行(共享 tag_list 等内部对象)")
    raise

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

# ==================== 状态 ====================
class BridgeState:
    def __init__(self):
        self.token: Optional[str] = None
        self.login_lock = threading.Lock()
        # 当前选定的 DRAFT 出库单
        self.selected_order_id: Optional[int] = None
        self.selected_order_no: Optional[str] = None
        # 期望扫码清单
        self.expected_epcs: List[dict] = []
        # 已扫过的 EPC(本地 cache,跨刷新)
        self.scanned_cache: Dict[str, dict] = {}  # epc -> ScanResult
        # 锁
        self.lock = threading.Lock()

state = BridgeState()

# ==================== HTTP 客户端 ====================
def _http_post(path, body=None, headers=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        WMS_BASE_URL + path,
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
        WMS_BASE_URL + path,
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
def login():
    """登录 WMS 后端,拿 token。失败最多重试 3 次,间隔 2 秒。"""
    with state.login_lock:
        for attempt in range(1, 4):
            r = _http_post("/api/auth/login",
                           {"username": WMS_USERNAME, "password": WMS_PASSWORD})
            if r.get("code") == 0 and r.get("data", {}).get("token"):
                state.token = r["data"]["token"]
                log.info("[LOGIN] success, role=%s", r["data"].get("role"))
                return True
            log.warning("[LOGIN] attempt %d/3 failed: %s",
                        attempt, r.get("message") or r.get("code"))
            state.token = None
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
    """调后端 scan-epc 接口。EPC 必须是 24 位 hex(12 字节 = 96 bit UHF Gen2)。"""
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
                   {"epc": e, "operator": WMS_USERNAME},
                   headers=hdrs or {})
    if r.get("code") == 0:
        return r.get("data", {"matched": False, "reason": "返回为空"})
    return {"matched": False, "reason": r.get("message", "后端错误")}

# ==================== 后台:tag_list → WMS ====================
def scan_pump_loop():
    """监听 tag_list 新增,把每个新 EPC 推给 WMS。"""
    last_seen = 0
    last_pushed = set()
    while True:
        try:
            with tag_list_lock:
                current = list(tag_list)
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
                        if not is_reading:
                            continue
                        result = scan_epc(cur_order_id, epc)
                        result["_epc"] = epc
                        result["_ts"] = item.get("timestamp") or datetime.now().strftime("%Y-%m-%d %H:%M:%S")
                        with state.lock:
                            state.scanned_cache[epc] = result
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
                    last_seen = len(current)
        except Exception as e:
            log.exception("[SCAN-PUMP] error")
        time.sleep(0.3)

# ==================== Web UI 接入(供 app.py 调用) ====================
def select_order(order_id):
    """Web UI 选定出库单 → 拉期望清单 → 更新状态"""
    with state.lock:
        state.selected_order_id = order_id
        state.scanned_cache.clear()
    if order_id is None:
        state.expected_epcs = []
        return True
    state.expected_epcs = load_expected_epcs(order_id)
    log.info("[SELECT] order_id=%d, expected=%d EPCs", order_id, len(state.expected_epcs))
    return True

def get_state_snapshot():
    """Web UI 拉一次状态(出库单列表 + 选定 + 期望 + 已扫)"""
    with state.lock:
        return {
            "loggedIn": state.token is not None,
            "selectedOrderId": state.selected_order_id,
            "selectedOrderNo": state.selected_order_no,
            "expectedCount": len(state.expected_epcs),
            "scannedCount": len(state.scanned_cache),
            "scanned": list(state.scanned_cache.values())[-30:],  # 最近 30 条
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
    log.info("  WMS endpoint: %s", WMS_BASE_URL)
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
