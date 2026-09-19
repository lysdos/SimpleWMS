package com.wms.dao;

import com.wms.model.Warehouse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class WarehouseDao {

    private final JdbcTemplate jdbc;
    public WarehouseDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 列表/详情带出各自名下库位数(相关子查询,warehouse 表很小,不值得预聚合) */
    private static final String SELECT_WITH_COUNT =
            "SELECT w.*, (SELECT COUNT(*) FROM location l WHERE l.warehouse_id = w.id) AS location_count " +
            "FROM warehouse w WHERE 1=1";

    private static final RowMapper<Warehouse> ROW = (rs, n) -> {
        Warehouse w = new Warehouse();
        w.setId(rs.getInt("id"));
        w.setCode(rs.getString("code"));
        w.setName(rs.getString("name"));
        w.setRemark(rs.getString("remark"));
        w.setEnabled(rs.getBoolean("enabled"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        w.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("updated_at");
        w.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        w.setLocationCount(rs.getInt("location_count"));
        return w;
    };

    public List<Warehouse> findAll(String keyword) {
        return findAll(keyword, null);
    }

    public List<Warehouse> findAll(String keyword, String status) {
        StringBuilder sb = new StringBuilder(SELECT_WITH_COUNT);
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (w.code LIKE ? OR w.name LIKE ? OR w.remark LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like); args.add(like);
        }
        if ("enabled".equals(status)) {
            sb.append(" AND w.enabled = TRUE");
        } else if ("disabled".equals(status)) {
            sb.append(" AND w.enabled = FALSE");
        }
        sb.append(" ORDER BY w.id ASC");
        return jdbc.query(sb.toString(), ROW, args.toArray());
    }

    public Warehouse findById(int id) {
        List<Warehouse> list = jdbc.query(SELECT_WITH_COUNT + " AND w.id = ?", ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Warehouse findByCode(String code) {
        List<Warehouse> list = jdbc.query(SELECT_WITH_COUNT + " AND w.code = ?", ROW, code);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 名下库位数(删仓库前的保护校验用) */
    public int countLocations(int warehouseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM location WHERE warehouse_id = ?", Integer.class, warehouseId);
        return n == null ? 0 : n;
    }

    public int insert(Warehouse w) {
        String sql = "INSERT INTO warehouse (code, name, remark, enabled) VALUES (?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, w.getCode());
            ps.setString(2, w.getName());
            ps.setString(3, w.getRemark());
            ps.setBoolean(4, w.getEnabled() == null ? Boolean.TRUE : w.getEnabled());
            return ps;
        }, kh);
        return GeneratedKeys.id(kh);
    }

    public int update(Warehouse w) {
        return jdbc.update(
                "UPDATE warehouse SET code=?, name=?, remark=?, enabled=?, " +
                "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                w.getCode(), w.getName(), w.getRemark(),
                w.getEnabled() == null ? Boolean.TRUE : w.getEnabled(), w.getId());
    }

    public int delete(int id) {
        return jdbc.update("DELETE FROM warehouse WHERE id = ?", id);
    }
}
