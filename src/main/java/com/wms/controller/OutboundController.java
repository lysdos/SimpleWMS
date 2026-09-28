package com.wms.controller;

import com.wms.dao.LocationDao;
import com.wms.model.ApiResponse;
import com.wms.model.ExpectedEpc;
import com.wms.model.Location;
import com.wms.model.InventoryLog;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.model.ScanResult;
import com.wms.service.OutboundService;
import com.wms.util.Csv;
import com.wms.util.Dates;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outbound")
public class OutboundController {

    private final OutboundService outboundService;
    private final LocationDao locationDao;

    @Autowired
    public OutboundController(OutboundService outboundService, LocationDao locationDao) {
        this.outboundService = outboundService;
        this.locationDao = locationDao;
    }

    /**
     * 库位码 → 仓库名。CSV 是后端拼的,拿不到前端的 locMap,所以要自己查一次。
     * 库存单元只存库位码快照,不带仓库名 —— 不带这个映射,导出的「库位」列就还是光秃秃一个码,
     * 看不出货在哪个仓(前端 locLabel 同理)。
     */
    private Map<String, String> warehouseByCode() {
        Map<String, String> map = new HashMap<>();
        for (Location l : locationDao.findAll(null, null, null)) {
            if (l.getCode() != null && l.getWarehouseName() != null && !l.getWarehouseName().isBlank()) {
                map.put(l.getCode(), l.getWarehouseName());
            }
        }
        return map;
    }

    // 早先还有一个 POST /api/outbound(单货出库)的老接口,2026-09-24 删除 —— 与入库侧对称:
    // 全仓库没有调用方(Web 建单走 /batch,PDA 与 UHF 桥接走 /orders、/dispatches;测试零引用,
    // nginx 日志也没有它的请求)。要出单件,传单元素数组给 /batch 即可。
    // 它没有入库那条的事务隐患(doOutbound 自己标了 @Transactional),所以这次纯粹是清死入口。

    /**
     * 新接口:批量出库
     * 入参:{ customer, externalNo, remark, items:[{productId,quantity,rowRemark}] }
     * 返回:完整出库单(含明细)
     */
    @PostMapping("/batch")
    public ApiResponse<OutboundOrder> outboundBatch(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String createdBy = currentUsername(req);
        String customer   = asString(body.get("customer"));
        String externalNo = asString(body.get("externalNo"));
        String orderRemark = asString(body.get("remark"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawItems = (List<Map<String, Object>>) body.get("items");
        if (rawItems == null || rawItems.isEmpty()) {
            throw new IllegalArgumentException("请至少添加一条出库明细");
        }
        List<OutboundService.BatchItem> items = new ArrayList<>();
        for (Map<String, Object> r : rawItems) {
            OutboundService.BatchItem it = new OutboundService.BatchItem();
            it.productId  = asInt(r.get("productId"));
            it.quantity   = asInt(r.get("quantity"));
            it.rowRemark  = asString(r.get("rowRemark"));
            items.add(it);
        }
        OutboundOrder order = outboundService.doOutboundBatch(items, customer, externalNo, orderRemark, createdBy);
        return ApiResponse.ok(order);
    }

    /** 出库单详情(弹窗展示) */
    @GetMapping("/orders/{id}")
    public ApiResponse<OutboundOrder> getOrder(@PathVariable int id) {
        OutboundOrder o = outboundService.getOrder(id);
        if (o == null) throw new NotFoundException("出库单不存在, id=" + id);
        return ApiResponse.ok(o);
    }

    /** 出库单列表(分页 + 多维筛选 + status 过滤) */
    @GetMapping("/orders")
    public ApiResponse<Map<String, Object>> listOrders(
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String createdBy,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        int ps = (pageSize <= 0 || pageSize > 200) ? 20 : pageSize;
        int p  = page <= 0 ? 1 : page;
        var list = outboundService.listOrders(beginDate, endDate, keyword, createdBy, status, p, ps);
        long total = outboundService.countOrders(beginDate, endDate, keyword, createdBy, status);
        return ApiResponse.ok(Map.of(
                "list", list,
                "total", total,
                "page", p,
                "pageSize", ps
        ));
    }

    /** 新建空草稿单(供 PDA 选/建) */
    @PostMapping("/orders")
    public ApiResponse<OutboundOrder> createDraft(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String createdBy = currentUsername(req);
        String customer   = asString(body.get("customer"));
        String externalNo = asString(body.get("externalNo"));
        String remark     = asString(body.get("remark"));
        return ApiResponse.ok(outboundService.createDraft(customer, externalNo, remark, createdBy));
    }

    /** PDA 扫一个码,验证并加入草稿单 */
    @PostMapping("/orders/{id}/scan")
    public ApiResponse<OutboundOrder> scanCode(@PathVariable int id, @RequestBody Map<String, Object> body) {
        String code = asString(body.get("code"));
        if (code == null || code.isBlank()) throw new IllegalArgumentException("请提供 24 位码");
        return ApiResponse.ok(outboundService.scanCodeToDraft(id, code));
    }

    // ====== UHF RFID 出库扫码集成 ======
    /**
     * 拉取出库单期望扫码清单。
     * UHF-Rader 调此接口,获取"应该扫哪些 EPC"。前端用此弹窗列出。
     */
    @GetMapping("/orders/{id}/expect")
    public ApiResponse<List<ExpectedEpc>> loadExpected(
            @PathVariable int id) {
        return ApiResponse.ok(outboundService.loadExpectedEpcs(id));
    }

    /**
     * UHF-Rader 扫到 EPC 后调用此接口,后端(方案 A:建单时就锁码,这里只做确认):
     *  1) 校验 EPC 是 24 位十六进制
     *  2) 查 inventory_item 必须是 status=RESERVED 且 outbound_order_id=本单;
     *     命中不了则按具体状态给出原因(别的单锁了/已出库/PENDING/...)
     *  3) 写一条 SCAN 标记流水(quantity=0,不扣库存);**扣减在 commit 时做**
     *  幂等:同一个 EPC 重复扫返回 alreadyScanned=true,不算错误。
     * body: { "epc": "FE019178", "createdBy": "admin" 可选 }
     */
    @PostMapping("/orders/{id}/scan-epc")
    public ApiResponse<ScanResult> scanEpc(
            @PathVariable int id,
            @RequestBody Map<String, Object> body,
            HttpServletRequest req) {
        String epc = asString(body.get("epc"));
        String createdBy = asString(body.get("createdBy"));
        if (createdBy == null || createdBy.isBlank()) createdBy = currentUsername(req);
        return ApiResponse.ok(outboundService.scanEpc(id, epc, createdBy));
    }

    /** 提交草稿单(DRAFT → COMMITTED) */
    @PostMapping("/orders/{id}/commit")
    public ApiResponse<OutboundOrder> commit(@PathVariable int id) {
        return ApiResponse.ok(outboundService.commitDraft(id));
    }

    /**
     * 老 DRAFT 单补全锁定(给方案 A 上线前已存在的草稿单用):
     *  按 FIFO 把每条明细对应的 SKU 库存锁到 RESERVED。
     *  库存不足会抛 IllegalStateException,事务回滚。
     */
    @PostMapping("/orders/{id}/reserve")
    public ApiResponse<OutboundOrder> reserve(@PathVariable int id) {
        return ApiResponse.ok(outboundService.reserveDraft(id));
    }

    /**
     * 撤销本单的全部扫码确认(清 SCAN 日志),用于错扫/重扫恢复。
     * 只对 DRAFT 单生效;不改库存单元状态,也不动 IN/OUT 真实流水。
     * UHF 终端的「清空」按钮会调这里,否则清完界面后同一 EPC 仍会被
     * 「EPC 已扫过(本单)」挡住,无法重扫。
     */
    @PostMapping("/orders/{id}/reset-scans")
    public ApiResponse<Map<String, Object>> resetScans(@PathVariable int id) {
        int deleted = outboundService.resetScans(id);
        Map<String, Object> data = new HashMap<>();
        data.put("orderId", id);
        data.put("deleted", deleted);
        return ApiResponse.ok(data);
    }

    /** 修改 DRAFT 出库单头(客户/外部单号/备注),仅 DRAFT 可改。 */
    @PutMapping("/orders/{id}")
    public ApiResponse<OutboundOrder> updateHeader(@PathVariable int id,
                                                    @RequestBody Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("请求体不能为空");
        return ApiResponse.ok(outboundService.updateDraftHeader(id,
                asString(body.get("customer")), asString(body.get("externalNo")),
                asString(body.get("remark"))));
    }

    /**
     * 往 DRAFT 出库单追加一条明细(同时按方案 A 锁定库存单元)。
     * body: { "productId": 3, "quantity": 2, "rowRemark": "可选" }
     */
    @PostMapping("/orders/{id}/items")
    public ApiResponse<OutboundOrder> addItem(@PathVariable int id,
                                              @RequestBody Map<String, Object> body) {
        if (body == null) throw new IllegalArgumentException("请求体不能为空");
        OutboundService.BatchItem it = new OutboundService.BatchItem();
        it.productId = asInt(body.get("productId"));
        it.quantity = asInt(body.get("quantity"));
        it.rowRemark = asString(body.get("rowRemark"));
        return ApiResponse.ok(outboundService.addItemToDraft(id, it));
    }

    /** 取消草稿单(DRAFT → CANCELLED) */
    @PostMapping("/orders/{id}/cancel")
    public ApiResponse<Void> cancel(@PathVariable int id) {
        outboundService.cancelDraft(id);
        return ApiResponse.ok(null);
    }

    // ====== UHF RFID 出库单派单 ======

    /**
     * 前台 WMS 下发出库单到指定 UHF 设备。
     * body: { "deviceCode": "MOCK-UHF-001" }
     * 只有 DRAFT 状态、未被下发过的出库单可下发。
     */
    @PostMapping("/orders/{id}/dispatch")
    public ApiResponse<OutboundOrder> dispatch(@PathVariable int id, @RequestBody Map<String, Object> body,
                                                HttpServletRequest req) {
        String deviceCode = asString(body.get("deviceCode"));
        // 派单审计字段:用当前登录用户名,而非写死 "admin"
        return ApiResponse.ok(outboundService.dispatchToUhf(id, deviceCode, currentUsername(req)));
    }

    /**
     * UHF 设备拉取"待认领的派单"列表。
     * query: ?deviceCode=XXX&limit=20
     *  - deviceCode 不传 = 拉全部未认领的派单(任意设备可认领)
     *  - deviceCode 传具体值 = 只拉发给该设备的单
     */
    @GetMapping("/dispatches")
    public ApiResponse<List<OutboundOrder>> listDispatches(
            @RequestParam(required = false) String deviceCode,
            @RequestParam(defaultValue = "50") int limit) {
        int lim = (limit <= 0 || limit > 200) ? 50 : limit;
        return ApiResponse.ok(outboundService.listDispatched(deviceCode, lim));
    }

    /**
     * UHF 设备认领一个派单(锁定该单到本设备)。
     * query: ?deviceCode=XXX
     */
    @PostMapping("/dispatches/{id}/claim")
    public ApiResponse<OutboundOrder> claimDispatch(
            @PathVariable int id,
            @RequestParam String deviceCode) {
        return ApiResponse.ok(outboundService.claimDispatch(id, deviceCode));
    }

    /** 出库单导出 CSV(明细) */
    @GetMapping(value = "/orders/{id}/export.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv(@PathVariable int id) {
        OutboundOrder o = outboundService.getOrder(id);
        if (o == null) throw new NotFoundException("出库单不存在, id=" + id);

        byte[] body = buildCsv(o, warehouseByCode()).getBytes(StandardCharsets.UTF_8);
        String fname = URLEncoder.encode(
                (o.getOrderNo() == null ? ("outbound_" + id) : o.getOrderNo()) + ".csv",
                StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", fname);
        return new ResponseEntity<>(body, headers, 200);
    }

    /** 批量导出出库单为 ZIP(每个订单一个 CSV) */
    @PostMapping("/orders/export.zip")
    public ResponseEntity<byte[]> exportZip(@RequestBody List<Integer> ids) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("请选择要导出的订单");
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            Map<String, String> wh = warehouseByCode();   // 整批查一次,不在循环里重复查
            try (ZipOutputStream zos = new ZipOutputStream(baos)) {
                for (int id : ids) {
                    OutboundOrder o = outboundService.getOrder(id);
                    if (o == null) continue;
                    String csvContent = buildCsv(o, wh);
                    String entryName = (o.getOrderNo() == null ? ("outbound_" + id) : o.getOrderNo()) + ".csv";
                    zos.putNextEntry(new ZipEntry(entryName));
                    zos.write(csvContent.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
            }
            byte[] zipBytes = baos.toByteArray();
            String fname = URLEncoder.encode("outbound_orders.zip", StandardCharsets.UTF_8).replace("+", "%20");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            headers.setContentDispositionFormData("attachment", fname);
            return new ResponseEntity<>(zipBytes, headers, 200);
        } catch (Exception e) {
            throw new RuntimeException("导出失败: " + e.getMessage(), e);
        }
    }

    private String buildCsv(OutboundOrder o, Map<String, String> warehouseByCode) {
        StringBuilder sb = new StringBuilder();
        sb.append('﻿');
        sb.append("出库单号,").append(csv(o.getOrderNo())).append('\n');
        sb.append("领用方,").append(csv(o.getCustomer())).append('\n');
        sb.append("外部单号,").append(csv(o.getExternalNo())).append('\n');
        sb.append("创建人,").append(csv(o.getCreatedBy())).append('\n');
        sb.append("整单备注,").append(csv(o.getRemark())).append('\n');
        sb.append("创建时间,").append(csv(Dates.fmt(o.getCreatedAt()))).append('\n');
        sb.append("总数量,").append(o.getTotalQuantity() == null ? 0 : o.getTotalQuantity()).append('\n');
        sb.append("明细数,").append(o.getItemCount() == null ? 0 : o.getItemCount()).append('\n');
        sb.append('\n');
        sb.append("序号,SKU,商品名称,库位,数量,单价,行备注\n");
        int idx = 1;
        if (o.getItems() != null) {
            for (OutboundOrderItem it : o.getItems()) {
                sb.append(idx++).append(',')
                  .append(csv(it.getSku())).append(',')
                  .append(csv(it.getProductName())).append(',')
                  .append(csv(locationCell(it, warehouseByCode))).append(',')
                  .append(it.getQuantity() == null ? 0 : it.getQuantity()).append(',')
                  .append(it.getUnitPrice() == null ? "0.00" : it.getUnitPrice().toPlainString()).append(',')
                  .append(csv(it.getRowRemark())).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 「库位」列内容。行上带了库位(手工指定的历史单)就直接用它;
     * 留空表示「自动」—— 此时把真实拣货分布写出来,否则导出/打印出来
     * 现场不知道去哪儿拿货。
     */
    private static String locationCell(OutboundOrderItem it, Map<String, String> warehouseByCode) {
        if (it.getLocation() != null && !it.getLocation().isBlank()) {
            return locLabel(it.getLocation(), warehouseByCode);
        }
        Map<String, Integer> picks = it.getPicks();
        if (picks == null || picks.isEmpty()) return "自动";
        StringBuilder s = new StringBuilder("自动(");
        boolean first = true;
        for (Map.Entry<String, Integer> e : picks.entrySet()) {
            if (!first) s.append(" / ");
            s.append(locLabel(e.getKey(), warehouseByCode)).append('×').append(e.getValue());
            first = false;
        }
        return s.append(')').toString();
    }

    /**
     * 库位显示:「主仓库 / A-01-01」;查不到仓库名就退回原码(库位已删/未分配仓库)。
     * 空库位码统一说「未指定库位」—— 与前端出库详情的拣货分组抬头保持一致。
     */
    private static String locLabel(String code, Map<String, String> warehouseByCode) {
        if (code == null || code.isBlank()) return "未指定库位";
        String wh = warehouseByCode == null ? null : warehouseByCode.get(code);
        return (wh == null || wh.isBlank()) ? code : wh + " / " + code;
    }

    /** 转义规则统一在 {@link Csv}(原先 4 个 controller 各抄一份),这里只做转发,不动调用点 */
    private static String csv(Object v) {
        return Csv.esc(v);
    }

    private static String currentUsername(HttpServletRequest req) {
        String op = (String) req.getAttribute("username");
        return (op == null || op.isBlank()) ? "操作员" : op;
    }

    private static Integer asInt(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.parseInt(o.toString().trim()); }
        catch (Exception e) { return null; }
    }
    private static String asString(Object o) {
        return o == null ? null : o.toString().trim();
    }
}
