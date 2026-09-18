package com.wms.dao;

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
import java.util.List;
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

    private void attachSuppliers(Product p) {
        if (p == null) return;
        List<ProductSupplier> suppliers = supplierDao.findSuppliersByProduct(p.getId());
        if (suppliers == null || suppliers.isEmpty()) return;
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

    public List<Product> findAll(String keyword) {
        return findAll(keyword, null);
    }

    public List<Product> findAll(String keyword, String supplier) {
        StringBuilder sb = new StringBuilder("SELECT * FROM product WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (sku LIKE ? OR name LIKE ? OR category LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if (supplier != null && !supplier.isBlank()) {
            sb.append(" AND default_supplier = ?");
            args.add(supplier);
        }
        sb.append(" ORDER BY id DESC");
        List<Product> list = jdbc.query(sb.toString(), ROW, args.toArray());
        for (Product p : list) attachSuppliers(p);
        return list;
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
            ps.setString(5, emptyTo(p.getLocation(), "默认库位"));
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

    public int update(Product p) {
        String sql = "UPDATE product SET sku=?, name=?, category=?, unit=?, location=?, " +
                "quantity=?, unit_price=?, low_stock=?, default_shelf_life_days=?, expiry_warn_days=?, " +
                "remark=? WHERE id=?";
        return jdbc.update(sql,
                p.getSku(), p.getName(), emptyTo(p.getCategory(), "默认"),
                emptyTo(p.getUnit(), "件"), emptyTo(p.getLocation(), "默认库位"),
                p.getQuantity(), p.getUnitPrice(), p.getLowStock(),
                p.getDefaultShelfLifeDays(), p.getExpiryWarnDays(),
                p.getRemark(), p.getId());
    }

    public int delete(int id) {
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