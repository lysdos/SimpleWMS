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
import java.util.Map;
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
        return u;
    };

    @Autowired
    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AppUser> findByUsername(String username) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, username, password_hash, role, enabled, must_change_pw " +
                    "FROM app_user WHERE username = ?", ROW, username));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<AppUser> findById(int id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT id, username, password_hash, role, enabled, must_change_pw " +
                    "FROM app_user WHERE id = ?", ROW, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public List<AppUser> listAll() {
        return jdbc.query(
            "SELECT id, username, password_hash, role, enabled, must_change_pw " +
            "FROM app_user ORDER BY id", ROW);
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
        // 先尝试从 keys 取
        Map<String, Object> keys = kh.getKeys();
        if (keys != null && keys.get("id") != null) {
            return ((Number) keys.get("id")).intValue();
        }
        // 回退:按 username 取(防止某些驱动拿不到 generated keys)
        return findByUsername(u.getUsername()).map(AppUser::getId).orElse(0);
    }

    public int updatePassword(int id, String hash, boolean mustChangePw) {
        return jdbc.update(
            "UPDATE app_user SET password_hash = ?, must_change_pw = ? WHERE id = ?",
            hash, mustChangePw, id);
    }

    public int setEnabled(int id, boolean enabled) {
        return jdbc.update(
            "UPDATE app_user SET enabled = ? WHERE id = ?", enabled, id);
    }

    public int delete(int id) {
        return jdbc.update("DELETE FROM app_user WHERE id = ?", id);
    }
}