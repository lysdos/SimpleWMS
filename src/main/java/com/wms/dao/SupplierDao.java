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
import java.util.List;
import java.util.Map;

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
        StringBuilder sb = new StringBuilder("SELECT * FROM supplier WHERE 1=1");
        Object[] args;
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (name LIKE ? OR contact LIKE ? OR phone LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args = new Object[]{like, like, like};
        } else {
            args = new Object[]{};
        }
        sb.append(" ORDER BY id DESC");
        return jdbc.query(sb.toString(), ROW, args);
    }

    public Supplier findById(int id) {
        List<Supplier> list = jdbc.query("SELECT * FROM supplier WHERE id = ?", ROW, id);
        return list.isEmpty() ? null : list.get(0);
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
        Number k = kh.getKey();
        if (k == null) {
            Map<String, Object> keys = kh.getKeys();
            if (keys != null && keys.get("id") != null) k = (Number) keys.get("id");
        }
        return k == null ? 0 : k.intValue();
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
