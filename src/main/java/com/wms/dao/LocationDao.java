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

    private static final RowMapper<Location> ROW = (rs, n) -> {
        Location l = new Location();
        l.setId(rs.getInt("id"));
        l.setCode(rs.getString("code"));
        l.setName(rs.getString("name"));
        l.setRemark(rs.getString("remark"));
        l.setEnabled(rs.getBoolean("enabled"));
        java.sql.Timestamp ts = rs.getTimestamp("created_at");
        l.setCreatedAt(ts == null ? null : ts.toLocalDateTime());
        ts = rs.getTimestamp("updated_at");
        l.setUpdatedAt(ts == null ? null : ts.toLocalDateTime());
        return l;
    };

    public List<Location> findAll(String keyword) {
        return findAll(keyword, null);
    }

    public List<Location> findAll(String keyword, String status) {
        StringBuilder sb = new StringBuilder("SELECT * FROM location WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND (code LIKE ? OR name LIKE ?)");
            String like = "%" + keyword.trim() + "%";
            args.add(like); args.add(like);
        }
        if ("enabled".equals(status)) {
            sb.append(" AND enabled = TRUE");
        } else if ("disabled".equals(status)) {
            sb.append(" AND enabled = FALSE");
        }
        sb.append(" ORDER BY code ASC");
        return jdbc.query(sb.toString(), ROW, args.toArray());
    }

    public Location findById(int id) {
        List<Location> list = jdbc.query("SELECT * FROM location WHERE id = ?", ROW, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public Location findByCode(String code) {
        List<Location> list = jdbc.query("SELECT * FROM location WHERE code = ?", ROW, code);
        return list.isEmpty() ? null : list.get(0);
    }

    public int insert(Location l) {
        String sql = "INSERT INTO location (code, name, remark, enabled) VALUES (?,?,?,?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, l.getCode());
            ps.setString(2, l.getName());
            ps.setString(3, l.getRemark());
            ps.setBoolean(4, l.getEnabled() == null ? Boolean.TRUE : l.getEnabled());
            return ps;
        }, kh);
        return GeneratedKeys.id(kh);
    }

    public int update(Location l) {
        return jdbc.update(
                "UPDATE location SET code=?, name=?, remark=?, enabled=?, " +
                "updated_at=CURRENT_TIMESTAMP WHERE id=?",
                l.getCode(), l.getName(), l.getRemark(),
                l.getEnabled() == null ? Boolean.TRUE : l.getEnabled(), l.getId());
    }

    public int delete(int id) {
        return jdbc.update("DELETE FROM location WHERE id = ?", id);
    }
}
