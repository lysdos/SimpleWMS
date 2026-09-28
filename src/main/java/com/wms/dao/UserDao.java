package com.wms.dao;

import com.wms.model.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class UserDao {

    private final JdbcTemplate jdbc;

    private static final RowMapper<AppUser> ROW = (rs, n) -> {
        AppUser u = new AppUser();
        u.setId(rs.getInt("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setRole(rs.getString("role"));
        u.setEnabled(rs.getBoolean("enabled"));
        u.setMustChangePw(rs.getBoolean("must_change_pw"));
        u.setCreatedBy(rs.getString("created_by"));
        u.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
        return u;
    };

    @Autowired
    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, username, password_hash, role, enabled, must_change_pw, created_by, created_at " +
                    "FROM app_user WHERE username = ?", ROW, username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AppUser> findById(int id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, username, password_hash, role, enabled, must_change_pw, created_by, created_at " +
                    "FROM app_user WHERE id = ?", ROW, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /** 只取状态字段,供过滤器每请求回查(不取 hash 减传输)。 */
    public Optional<UserStatus> findStatusById(int id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, enabled, must_change_pw, role FROM app_user WHERE id = ?",
                    (rs, n) -> new UserStatus(rs.getInt("id"),
                                               rs.getBoolean("enabled"),
                                               rs.getBoolean("must_change_pw"),
                                               rs.getString("role")),
                    id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 轻量状态载体(给 UserStatusFilter 用)。
     * 带上 role:token 里的 role 只是**签发那一刻的快照**,改角色后要靠这次回查覆盖,
     * 否则被降级的人会一直用旧权限到 token 过期(默认 24h)。详见 UserStatusFilter。
     */
    public record UserStatus(int id, boolean enabled, boolean mustChangePw, String role) {}

    public List<AppUser> listAll() {
        return jdbc.query(
            "SELECT id, username, password_hash, role, enabled, must_change_pw, created_by, created_at " +
            "FROM app_user ORDER BY id", ROW);
    }

    public List<AppUser> findUsers(String keyword, String role, String status, String beginDate, String endDate) {
        StringBuilder sb = new StringBuilder(
            "SELECT id, username, password_hash, role, enabled, must_change_pw, created_by, created_at " +
            "FROM app_user WHERE 1=1");
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            sb.append(" AND username LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        if (role != null && !role.isBlank()) {
            sb.append(" AND role = ?");
            args.add(role);
        }
        if ("enabled".equals(status)) {
            sb.append(" AND enabled = TRUE");
        } else if ("disabled".equals(status)) {
            sb.append(" AND enabled = FALSE");
        }
        if (beginDate != null && !beginDate.isBlank()) {
            sb.append(" AND created_at >= ?");
            args.add(beginDate + " 00:00:00");
        }
        if (endDate != null && !endDate.isBlank()) {
            sb.append(" AND created_at <= ?");
            args.add(endDate + " 23:59:59");
        }
        sb.append(" ORDER BY id");
        return jdbc.query(sb.toString(), ROW, args.toArray());
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM app_user", Long.class);
        return c == null ? 0 : c;
    }

    public int insert(AppUser u) {
        String sql = "INSERT INTO app_user (username, password_hash, role, enabled, must_change_pw) " +
                     "VALUES (?, ?, ?, ?, ?)";
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, u.getUsername());
            ps.setString(2, u.getPasswordHash());
            ps.setString(3, u.getRole());
            ps.setBoolean(4, u.getEnabled() != null ? u.getEnabled() : Boolean.TRUE);
            ps.setBoolean(5, u.getMustChangePw() != null ? u.getMustChangePw() : Boolean.FALSE);
            return ps;
        }, kh);
        int id = GeneratedKeys.id(kh);
        // 回退:按 username 取(防止某些驱动拿不到 generated keys)
        return id != 0 ? id : findByUsername(u.getUsername()).map(AppUser::getId).orElse(0);
    }

    public int updatePassword(int id, String hash, boolean mustChangePw) {
        return jdbc.update(
            "UPDATE app_user SET password_hash = ?, must_change_pw = ? WHERE id = ?",
            hash, mustChangePw, id);
    }

    public int updateRole(int id, String role) {
        return jdbc.update(
            "UPDATE app_user SET role = ? WHERE id = ?",
            role, id);
    }

    public int setEnabled(int id, boolean enabled) {
        return jdbc.update(
            "UPDATE app_user SET enabled = ? WHERE id = ?", enabled, id);
    }

    public int delete(int id) {
        return jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
}