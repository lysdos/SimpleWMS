package com.wms.dao;

import com.wms.model.Location;
import com.wms.model.Product;
import com.wms.model.ProductSupplier;
import com.wms.model.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class ProductDao {

    private final JdbcTemplate jdbc;
    private final SupplierDao supplierDao;

    private static final RowMapper<Product> ROW = (rs, n) -> {
        Product p = new Product();
        p.setId(rs.getInt("id"));
        p.setSku(rs.getString("sku"));
        p.setName(rs.getString("name"));
        p.setCategory(rs.getString("category"));
        p.setUnit(rs.getString("unit"));
        p.setLocation(rs.getString("location"));
        p.setQuantity(rs.getInt("quantity"));
        p.setUnitPrice(rs.getBigDecimal("unit_price"));
        p.setLowStock(rs.getInt("low_stock"));
        int dsld = rs.getInt("default_shelf_life_days");
        p.setDefaultShelfLifeDays(rs.wasNull() ? null : dsld);
        int ewd = rs.getInt("expiry_warn_days");
        p.setExpiryWarnDays(rs.wasNull() ? null : ewd);
        p.setRemark(rs.getString("remark"));
        p.setCreatedAt(rs.getTimestamp("created_at") == null
                ? null : rs.getTimestamp("created_at").toLocalDateTime());
        p.setUpdatedAt(rs.getTimestamp("updated_at") == null
                ? null : rs.getTimestamp("updated_at").toLocalDateTime());
        return p;
    };

    @Autowired
    public ProductDao(JdbcTemplate jdbc, SupplierDao supplierDao) {
        this.jdbc = jdbc;
        this.supplierDao = supplierDao;
    }

    /** 把一组关联行填进一个商品(单商品路径与批量路径共用这段映射逻辑) */
    private static void fillSuppliers(Product p, List<ProductSupplier> suppliers) {
        if (p == null || suppliers == null || suppliers.isEmpty()) return;
        List<Product.SupplierRef> refs = new ArrayList<>();
        for (ProductSupplier ps : suppliers) {
            refs.add(new Product.SupplierRef(ps.getSupplierId(), ps.getSupplierName(), ps.getIsDefault()));
            if (Boolean.TRUE.equals(ps.getIsDefault())) {
                p.setDefaultSupplierId(ps.getSupplierId());
                p.setDefaultSupplier(ps.getSupplierName());
            }
        }
        p.setSuppliers(refs);
    }

    private void attachSuppliers(Product p) {
        if (p == null) return;
        fillSuppliers(p, supplierDao.findSuppliersByProduct(p.getId()));
    }

    /**
     * 批量为整页商品填关联供应商 —— **一条 SQL 拿全部**,而不是每行查一次。
     *
     * 原来 findAll 是 `for (Product p : list) attachSuppliers(p)`,商品列表页一次
     * 200 行就是 201 次查询(典型的 N+1)。商家/供应商关联本身是多对多,一行一条关联行,
     * 所以按 product_id 分组后回填即可,顺序仍由 SQL 保证(默认在前、名称升序)。
     */
    private void attachSuppliers(List<Product> products) {
        if (products == null || products.isEmpty()) return;
        List<Integer> ids = new ArrayList<>();
        for (Product p : products) {
            if (p != null && p.getId() != null) ids.add(p.getId());
        }
        if (ids.isEmpty()) return;
        Map<Integer, List<ProductSupplier>> byProduct = new HashMap<>();
        for (ProductSupplier ps : supplierDao.findSuppliersByProducts(ids)) {
            byProduct.computeIfAbsent(ps.getProductId(), k -> new ArrayList<>()).add(ps);
        }
        for (Product p : products) {
            if (p != null) fillSuppliers(p, byProduct.get(p.getId()));
        }
    }

    public List<Product> findAll(String keyword) {
        return findAll(keyword, null);
    }

    public List<Product> findAll(String keyword, String supplier) {
        Where w = where(keyword, supplier);
        List<Product> list = jdbc.query("SELECT * FROM product" + w.sql() + " ORDER BY id DESC",
                ROW, w.args().toArray());
        attachSuppliers(list);   // 一条 SQL 批量回填(原来是每行一次查询)
        return list;
    }

    /**
     * 分页查询。**条件与 {@link #count} 共用同一份 {@link #where}**。
     *
     * 为什么强调共用:分页必须同时有「取一页」和「总数」两条 SQL,条件抄两份的话,
     * 改一处漏一处就是"翻到第 3 页是空的,但总数说有 100 条"这类问题。
     * 出入库单那边已经踩过,注释里写着「改一处必须同改另一处」——
     * 这里直接用一份实现回避掉。
     */
    public List<Product> findPage(String keyword, String supplier, int offset, int limit) {
        Where w = where(keyword, supplier);
        List<Product> list = jdbc.query(
                "SELECT * FROM product" + w.sql() + " ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW, concat(w.args(), limit, offset));
        attachSuppliers(list);
        return list;
    }

    /** 与 {@link #findPage} 同条件的总数 */
    public long count(String keyword, String supplier) {
        Where w = where(keyword, supplier);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM product" + w.sql(), Long.class,
                w.args().toArray());
        return n == null ? 0 : n;
    }

    private record Where(String sql, java.util.List<Object> args) {}

    private static Where where(String keyword, String supplier) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (sku LIKE ? OR name LIKE ? OR category LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (supplier != null && !supplier.isBlank()) {
            // 商品↔供应商是多对多(product_supplier),列表里显示的也是这些关联,
            // 所以筛选按"关联过"匹配 —— 只认默认那一条的话,多选出来的非默认供应商永远搜不到。
            // (此处原先写的是 product.default_supplier,product 表没有这一列,一用就 500。)
            sb.append(" AND EXISTS (SELECT 1 FROM product_supplier ps JOIN supplier s ON s.id = ps.supplier_id"
                    + " WHERE ps.product_id = product.id AND s.name = ?)");
            args.add(supplier);
        }
        return new Where(sb.toString(), args);
    }

    /** 把分页参数接在条件参数后面(JDBC 按顺序绑) */
    private static Object[] concat(java.util.List<Object> args, Object... tail) {
        Object[] out = new Object[args.size() + tail.length];
        for (int i = 0; i < args.size(); i++) out[i] = args.get(i);
        System.arraycopy(tail, 0, out, args.size(), tail.length);
        return out;
    }

    public Optional<Product> findById(int id) {
        try {
            Product p = jdbc.queryForObject(
                    "SELECT * FROM product WHERE id = ?", ROW, id);
            if (p != null) attachSuppliers(p);
            return Optional.ofNullable(p);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 按 id 批量取(导出选中项用)。
     *
     * 一条 `IN (?,...)` 而不是循环调 findById:N 次往返在几百条时也是白白多花的时间。
     * 空集合直接返回空表 —— 拼成 `IN ()` 是语法错误。
     */
    public List<Product> findByIds(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return new java.util.ArrayList<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Product> list = jdbc.query(
                "SELECT * FROM product WHERE id IN (" + placeholders + ") ORDER BY id ASC",
                ROW, ids.toArray());
        attachSuppliers(list);   // 一条 SQL 批量回填,不是逐行查
        return list;
    }

    public Optional<Product> findBySku(String sku) {
        try {
            Product p = jdbc.queryForObject(
                    "SELECT * FROM product WHERE sku = ?", ROW, sku);
            if (p != null) attachSuppliers(p);
            return Optional.ofNullable(p);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int insert(Product p) {
        String sql = "INSERT INTO product (sku, name, category, unit, location, " +
                "quantity, unit_price, low_stock, default_shelf_life_days, expiry_warn_days, remark) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, p.getSku());
            ps.setString(2, p.getName());
            ps.setString(3, emptyTo(p.getCategory(), "默认"));
            ps.setString(4, emptyTo(p.getUnit(), "件"));
            ps.setString(5, emptyTo(p.getLocation(), Location.FALLBACK_CODE));
            ps.setInt(6, p.getQuantity() == null ? 0 : p.getQuantity());
            ps.setBigDecimal(7, p.getUnitPrice() == null ? BigDecimal.ZERO : p.getUnitPrice());
            ps.setInt(8, p.getLowStock() == null ? 10 : p.getLowStock());
            if (p.getDefaultShelfLifeDays() == null) ps.setNull(9, java.sql.Types.INTEGER); else ps.setInt(9, p.getDefaultShelfLifeDays());
            if (p.getExpiryWarnDays() == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, p.getExpiryWarnDays());
            ps.setString(11, p.getRemark());
            return ps;
        }, kh);
        return GeneratedKeys.id(kh);
    }

    /**
     * 更新商品资料。**故意不写 quantity** —— 库存数量只能由出入库/报废的
     * {@link #adjustQuantity} 改动。
     *
     * 以前这里带 `quantity=?`,而 controller 的 create/update 共用一个解析方法、把缺失的
     * quantity 补成 0:只要有人发一个不带 quantity 的 PUT(或编辑弹窗用的是打开时的旧值),
     * 就会把库存悄悄清零,而 inventory_item 一件没动 —— 仪表盘总量/总值/低库存全跟着错。
     * 数量不再是"商品资料"的一部分,改资料就不该碰它。
     */
    public int update(Product p) {
        String sql = "UPDATE product SET sku=?, name=?, category=?, unit=?, location=?, " +
                "unit_price=?, low_stock=?, default_shelf_life_days=?, expiry_warn_days=?, " +
                "remark=? WHERE id=?";
        return jdbc.update(sql,
                p.getSku(), p.getName(), emptyTo(p.getCategory(), "默认"),
                emptyTo(p.getUnit(), "件"), emptyTo(p.getLocation(), Location.FALLBACK_CODE),
                p.getUnitPrice(), p.getLowStock(),
                p.getDefaultShelfLifeDays(), p.getExpiryWarnDays(),
                p.getRemark(), p.getId());
    }

    public int delete(int id) {
        // product_supplier 在 schema 里没有外键(只有注释标注 → product.id),所以删商品时
        // 必须显式清关联行,否则会留下指向已删商品的孤儿记录。调用方 ProductService.delete
        // 带 @Transactional,两条语句同一事务。
        jdbc.update("DELETE FROM product_supplier WHERE product_id = ?", id);
        return jdbc.update("DELETE FROM product WHERE id = ?", id);
    }

    /** 库存 +/- 原子操作,返回受影响行数(0=商品不存在或扣成负数被拒绝) */
    public int adjustQuantity(int id, int delta) {
        return jdbc.update(
                "UPDATE product SET quantity = quantity + ?, updated_at = CURRENT_TIMESTAMP " +
                        "WHERE id = ? AND quantity + ? >= 0",
                delta, id, delta);
    }

    public long count() {
        Long v = jdbc.queryForObject("SELECT COUNT(*) FROM product", Long.class);
        return v == null ? 0 : v;
    }

    public Long sumQuantity() {
        Long v = jdbc.queryForObject("SELECT COALESCE(SUM(quantity),0) FROM product", Long.class);
        return v == null ? 0L : v;
    }

    public BigDecimal sumValue() {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity * unit_price),0) FROM product", BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    public long countLowStock() {
        Long v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM product WHERE quantity <= low_stock", Long.class);
        return v == null ? 0L : v;
    }

    private static String emptyTo(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}