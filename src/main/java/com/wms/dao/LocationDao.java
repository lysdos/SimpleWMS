package com.wms.dao;

import com.wms.model.Location;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

@Repository
public class LocationDao {

    private final JdbcTemplate jdbc;
    public LocationDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 库位列表/详情一律带出所属仓库名(仓库表很小,left join 无压力) */
    private static final String SELECT_WITH_WAREHOUSE =
            "SELECT l.*, w.name AS warehouse_name FROM location l " +
            "LEFT JOIN warehouse w ON w.id = l.warehouse_id WHERE 1=1";

    private static final RowMapper<Location> ROW = (rs, n) -> {
        Location l = new Location();
        l.setId(rs.getInt("id"));
        l.setCode(rs.getString("code"));
        l.setName(rs.getString("name"));
        int wid = rs.getInt("warehouse_id");
        l.setWarehouseId(rs.wasNull() ? null : wid);
        l.setWarehouseName(rs.getString("warehouse_name"));
        l.setRemark(rs.getString("remark"));
        l.setEnabled(rs.getBoolean("enabled"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        l.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("updated_at");
        l.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return l;
    };

    public List<Location> findAll(String keyword) {
        return findAll(keyword, null, null);
    }

    public List<Location> findAll(String keyword, String status) {
        return findAll(keyword, status, null);
    }

    public List<Location> findAll(String keyword, String status, Integer warehouseId) {
        StringBuilder sb = new StringBuilder(SELECT_WITH_WAREHOUSE);
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (l.code LIKE ? OR l.name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like);
        }
        if (warehouseId != null) {
            sb.append(" AND l.warehouse_id = ?");
            args.add(warehouseId);
        }
        if ("enabled".equals(status)) {
            sb.append(" AND l.enabled = TRUE");
        } else if ("disabled".equals(status)) {
            sb.append(" AND l.enabled = FALSE");
        }
        sb.append(" ORDER BY l.code ASC");
        return jdbc.query(sb.toString(), ROW, args.toArray());
    }

    public Location findById(int id) {
        List<Location> list = jdbc.query(SELECT_WITH_WAREHOUSE + " AND l.id = ?", ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Location findByCode(String code) {
        List<Location> list = jdbc.query(SELECT_WITH_WAREHOUSE + " AND l.code = ?", ROW, code);
        return list.isEmpty() ? null : list.get(0);
    }

    public int insert(Location l) {
        String sql = "INSERT INTO location (code, name, warehouse_id, remark, enabled) VALUES (?,?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, l.getCode());
            ps.setString(2, l.getName());
            if (l.getWarehouseId() == null) ps.setNull(3, java.sql.Types.INTEGER);
            else ps.setInt(3, l.getWarehouseId());
            ps.setString(4, l.getRemark());
            ps.setBoolean(5, l.getEnabled() == null ? Boolean.TRUE : l.getEnabled());
            return ps;
        }, kh);
        return GeneratedKeys.id(kh);
    }

    public int update(Location l) {
        return jdbc.update(
                "UPDATE location SET code=?, name=?, warehouse_id=?, remark=?, enabled=?, " +
                "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                l.getCode(), l.getName(), l.getWarehouseId(), l.getRemark(),
                l.getEnabled() == null ? Boolean.TRUE : l.getEnabled(), l.getId());
    }

    public int delete(int id) {
        return jdbc.update("DELETE FROM location WHERE id = ?", id);
    }
}
