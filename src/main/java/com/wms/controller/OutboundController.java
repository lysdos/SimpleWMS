package com.wms.controller;

import com.wms.model.ApiResponse;
import com.wms.model.ExpectedEpc;
import com.wms.model.InventoryLog;
import com.wms.model.OutboundOrder;
import com.wms.model.OutboundOrderItem;
import com.wms.model.ScanResult;
import com.wms.service.OutboundService;
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
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/outbound")
public class OutboundController {

    private final OutboundService outboundService;

    @Autowired
    public OutboundController(OutboundService outboundService) {
        this.outboundService = outboundService;
    }

    /** 老接口:单货出库(兼容,内部走 DRAFT 路径) */
    @PostMapping
    public ApiResponse<OutboundOrder> outbound(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Integer productId = asInt(body.get("productId"));
        Integer quantity  = asInt(body.get("quantity"));
        String remark    = asString(body.get("remark"));
        String createdBy  = currentUsername(req);

        if (productId == null) throw new IllegalArgumentException("请选择商品");
        if (quantity == null)  throw new IllegalArgumentException("请输入出库数量");
        if (quantity <= 0)     throw new IllegalArgumentException("出库数量必须大于 0");

        return ApiResponse.ok(outboundService.doOutbound(productId, quantity, remark, createdBy));
    }

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
        if (o == null) throw new IllegalArgumentException("出库单不存在, id=" + id);
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
     * UHF-Rader 扫到 EPC 后调用此接口,后端:
     *  1) 校验 EPC 存在且 IN_STOCK
     *  2) 校验 EPC 所属 SKU 在出库单明细里(不在 → 不报警 success=false)
     *  3) 调 consumeForOutboundByEpc 扣 1 件 + 写 OUT 流水
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
        if (o == null) throw new IllegalArgumentException("出库单不存在, id=" + id);

        StringBuilder sb = new StringBuilder();
        sb.append('\ufeff');
        sb.append("出库单号,").append(csv(o.getOrderNo())).append('\n');
        sb.append("领用方,").append(csv(o.getCustomer())).append('\n');
        sb.append("外部单号,").append(csv(o.getExternalNo())).append('\n');
        sb.append("创建人,").append(csv(o.getCreatedBy())).append('\n');
        sb.append("整单备注,").append(csv(o.getRemark())).append('\n');
        sb.append("创建时间,").append(csv(o.getCreatedAt() == null ? "" : o.getCreatedAt().toString())).append('\n');
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
                  .append(csv(it.getLocation())).append(',')
                  .append(it.getQuantity() == null ? 0 : it.getQuantity()).append(',')
                  .append(it.getUnitPrice() == null ? "0.00" : it.getUnitPrice().toPlainString()).append(',')
                  .append(csv(it.getRowRemark())).append('\n');
            }
        }

        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        String fname = URLEncoder.encode(
                (o.getOrderNo() == null ? ("outbound_" + id) : o.getOrderNo()) + ".csv",
                StandardCharsets.UTF_8).replace("+", "%20");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDispositionFormData("attachment", fname);
        return new ResponseEntity<>(body, headers, 200);
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
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
