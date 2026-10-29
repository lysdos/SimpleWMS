package com.wms.dao;

import com.wms.model.ProductSupplier;
import com.wms.model.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

@Repository
public class SupplierDao {

    private final JdbcTemplate jdbc;
    public SupplierDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Supplier> ROW = (rs, n) -> {
        Supplier s = new Supplier();
        s.setId(rs.getInt("id"));
        s.setName(rs.getString("name"));
        s.setContact(rs.getString("contact"));
        s.setPhone(rs.getString("phone"));
        s.setEmail(rs.getString("email"));
        s.setAddress(rs.getString("address"));
        s.setRemark(rs.getString("remark"));
        s.setEnabled(rs.getBoolean("enabled"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        s.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("updated_at");
        s.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return s;
    };

    private static final RowMapper<ProductSupplier> PS_ROW = (rs, n) -> {
        ProductSupplier ps = new ProductSupplier();
        ps.setId(rs.getInt("id"));
        ps.setProductId(rs.getInt("product_id"));
        ps.setSupplierId(rs.getInt("supplier_id"));
        ps.setIsDefault(rs.getBoolean("is_default"));
        ps.setUnitPrice(rs.getBigDecimal("unit_price"));
        ps.setRemark(rs.getString("remark"));
        ps.setSupplierName(rs.getString("supplier_name"));
        return ps;
    };

    public List<Supplier> findAll(String keyword) {
        return findAll(keyword, null);
    }

    public List<Supplier> findAll(String keyword, String status) {
        Where w = where(keyword, status);
        return jdbc.query("SELECT * FROM supplier" + w.sql() + " ORDER BY id DESC", ROW, w.args().toArray());
    }

    /** 分页查询。条件与 {@link #count} 共用 {@link #where},避免两条 SQL 的条件漂移。 */
    public List<Supplier> findPage(String keyword, String status, int offset, int limit) {
        Where w = where(keyword, status);
        Object[] args = new Object[w.args().size() + 2];
        for (int i = 0; i < w.args().size(); i++) args[i] = w.args().get(i);
        args[w.args().size()] = limit;
        args[w.args().size() + 1] = offset;
        return jdbc.query("SELECT * FROM supplier" + w.sql() + " ORDER BY id DESC LIMIT ? OFFSET ?",
                ROW, args);
    }

    /** 与 {@link #findPage} 同条件的总数 */
    public long count(String keyword, String status) {
        Where w = where(keyword, status);
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM supplier" + w.sql(), Long.class, w.args().toArray());
        return n == null ? 0 : n;
    }

    private record Where(String sql, java.util.List<Object> args) {}

    private static Where where(String keyword, String status) {
        StringBuilder sb = new StringBuilder(" WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (name LIKE ? OR contact LIKE ? OR phone LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if ("enabled".equals(status)) {
            sb.append(" AND enabled = TRUE");
        } else if ("disabled".equals(status)) {
            sb.append(" AND enabled = FALSE");
        }
        return new Where(sb.toString(), args);
    }

    public Supplier findById(int id) {
        List<Supplier> list = jdbc.query("SELECT * FROM supplier WHERE id = ?", ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按 id 批量取(导出选中项用)。空集合返回空表 —— 拼成 `IN ()` 是语法错误。 */
    public List<Supplier> findByIds(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return new java.util.ArrayList<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query("SELECT * FROM supplier WHERE id IN (" + placeholders + ") ORDER BY id ASC",
                ROW, ids.toArray());
    }

    public int insert(Supplier s) {
        String sql = "INSERT INTO supplier (name, contact, phone, email, address, remark, enabled) " +
                "VALUES (?,?,?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, s.getName());
            ps.setString(2, s.getContact());
            ps.setString(3, s.getPhone());
            ps.setString(4, s.getEmail());
            ps.setString(5, s.getAddress());
            ps.setString(6, s.getRemark());
            ps.setBoolean(7, s.getEnabled() == null ? Boolean.TRUE : s.getEnabled());
            return ps;
        }, kh);
        return GeneratedKeys.id(kh);
    }

    public int update(Supplier s) {
        return jdbc.update(
                "UPDATE supplier SET name=?, contact=?, phone=?, email=?, address=?, remark=?, " +
                "enabled=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                s.getName(), s.getContact(), s.getPhone(), s.getEmail(), s.getAddress(),
                s.getRemark(), s.getEnabled() == null ? Boolean.TRUE : s.getEnabled(), s.getId());
    }

    public int delete(int id) {
        return jdbc.update("DELETE FROM supplier WHERE id = ?", id);
    }

    /** 商品-供应商关联 */
    public List<ProductSupplier> findSuppliersByProduct(int productId) {
        return jdbc.query(
                "SELECT ps.*, s.name AS supplier_name FROM product_supplier ps " +
                "JOIN supplier s ON ps.supplier_id = s.id " +
                "WHERE ps.product_id = ? ORDER BY ps.is_default DESC, s.name ASC",
                PS_ROW, productId);
    }

    /**
     * 一次取回多个商品的供应商关联,供商品列表页批量回填(消 N+1)。
     * 返回顺序:`product_id` 分组、组内**默认在前、名称升序** —— 与单商品版本一致,
     * 所以调用方按 productId 分组后直接顺序使用即可。
     *
     * 占位符按 id **个数**生成(不拼接任何传入字符串),参数走 `?` 绑定。
     */
    public List<ProductSupplier> findSuppliersByProducts(Collection<Integer> productIds) {
        if (productIds == null || productIds.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(productIds.size(), "?"));
        return jdbc.query(
                "SELECT ps.*, s.name AS supplier_name FROM product_supplier ps " +
                "JOIN supplier s ON ps.supplier_id = s.id " +
                "WHERE ps.product_id IN (" + placeholders + ") " +
                "ORDER BY ps.product_id ASC, ps.is_default DESC, s.name ASC",
                PS_ROW, productIds.toArray());
    }

    public ProductSupplier findDefaultSupplier(int productId) {
        List<ProductSupplier> list = jdbc.query(
                "SELECT ps.*, s.name AS supplier_name FROM product_supplier ps " +
                "JOIN supplier s ON ps.supplier_id = s.id " +
                "WHERE ps.product_id = ? AND ps.is_default = TRUE LIMIT 1",
                PS_ROW, productId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void replaceProductSuppliers(int productId, List<ProductSupplier> list) {
        // 先清,再批量插(同事务内调用,简化)
        jdbc.update("DELETE FROM product_supplier WHERE product_id = ?", productId);
        if (list == null || list.isEmpty()) return;
        // 保证默认唯一
        boolean hasDefault = list.stream().anyMatch(p -> Boolean.TRUE.equals(p.getIsDefault()));
        for (ProductSupplier ps : list) {
            boolean isDef = hasDefault ? Boolean.TRUE.equals(ps.getIsDefault()) : (ps == list.get(0));
            jdbc.update("INSERT INTO product_supplier (product_id, supplier_id, is_default, unit_price, remark) " +
                            "VALUES (?,?,?,?,?)",
                    productId, ps.getSupplierId(), isDef, ps.getUnitPrice(), ps.getRemark());
        }
    }
}
