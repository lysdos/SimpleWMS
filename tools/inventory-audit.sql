-- ============================================================
--  SimpleWMS 库存对账 SQL
--  用途: 比对 product.quantity 与 inventory_item 实际分布
--  规则(和后端代码保持一致):
--    - DRAFT 草稿单明细生成的 inventory_item 状态为 PENDING / remaining=0,
--      不计入 product.quantity, 也不计入真实在库
--    - 真实在库 = SUM(remaining WHERE status='IN_STOCK')
--    - 草稿占用 = SUM(quantity WHERE status='PENDING')  (DRAFT 提交时才转 IN_STOCK)
--    - 真实可用 = IN_STOCK - 其他 DRAFT 单占用的同 SKU
--  跑法:
--    docker exec -i simplewms-mysql mysql -uwms -pwmspass simple_wms < tools/inventory-audit.sql
-- ============================================================

SELECT
    p.id                              AS product_id,
    p.sku                             AS sku,
    p.name                            AS name,
    p.quantity                        AS product_quantity,
    COALESCE(SUM(CASE WHEN ii.status = 'IN_STOCK'  THEN ii.remaining END), 0) AS in_stock_qty,
    COALESCE(SUM(CASE WHEN ii.status = 'PENDING'   THEN ii.quantity   END), 0) AS draft_pending_qty,
    COALESCE(SUM(CASE WHEN ii.status = 'OUT'       THEN ii.remaining END), 0) AS out_qty,
    COALESCE(SUM(CASE WHEN ii.status = 'SCRAPPED'  THEN ii.remaining END), 0) AS scrapped_qty,
    -- diff: product.quantity 与真实在库的差异(应该 = 0;非 0 即历史脏数据或预扣未归还)
    (p.quantity - COALESCE(SUM(CASE WHEN ii.status = 'IN_STOCK' THEN ii.remaining END), 0)) AS diff_product_vs_instock,
    -- diff_with_draft: product.quantity 与「真实在库 + 草稿占用」总和的差异(草稿 PENDING 应当=0 时)
    (p.quantity - (
        COALESCE(SUM(CASE WHEN ii.status = 'IN_STOCK' THEN ii.remaining END), 0) +
        COALESCE(SUM(CASE WHEN ii.status = 'PENDING'  THEN ii.quantity   END), 0)
    )) AS diff_product_vs_instock_plus_draft
FROM product p
LEFT JOIN inventory_item ii ON ii.product_id = p.id
GROUP BY p.id, p.sku, p.name, p.quantity
ORDER BY ABS(diff_product_vs_instock_plus_draft) DESC, p.sku;

-- 单独拉草稿占用明细,按入库单聚合
SELECT
    o.id                  AS draft_order_id,
    o.order_no,
    o.status,
    ii.product_id,
    p.sku,
    p.name,
    SUM(ii.quantity)      AS draft_qty,
    o.created_at
FROM inbound_order o
JOIN inventory_item ii ON ii.inbound_order_id = o.id AND ii.status = 'PENDING'
JOIN product p ON p.id = ii.product_id
WHERE o.status = 'DRAFT'
GROUP BY o.id, o.order_no, o.status, ii.product_id, p.sku, p.name, o.created_at
ORDER BY o.id DESC;
