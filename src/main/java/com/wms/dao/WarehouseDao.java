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

    /** 按 id 批量取(导出选中项用)。空集合返回空表 —— 拼成 `IN ()` 是语法错误。 */
    public List<Warehouse> findByIds(java.util.Collection<Integer> ids) {
        if (ids == null || ids.isEmpty()) return new java.util.ArrayList<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return jdbc.query(SELECT_WITH_COUNT + " AND w.id IN (" + placeholders + ") ORDER BY w.id ASC",
                ROW, ids.toArray());
    }

    /** 名下库位数(删仓库前的保护校验用) */
    public int countLocations(int warehouseId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM location WHERE warehouse_id = ?", Integer.class, warehouseId);
        return n == null ? 0 : n;
    }

    /**
     * 删仓库前的保护校验:用**锁定读**而不是普通 COUNT。
     * 普通 COUNT 走事务快照,看不到期间刚提交的新库位,会放行删除并留下孤儿库位;
     * 锁定读读的是最新已提交版本,能看见并发新建的库位(并挡住它)。
     * location.warehouse_id 没有外键,这里是唯一能把窗口收窄的地方。
     */
    public int countLocationsLocking(int warehouseId) {
        List<Integer> ids = jdbc.queryForList(
                "SELECT id FROM location WHERE warehouse_id = ? FOR UPDATE", Integer.class, warehouseId);
        return ids.size();
    }

    /**
     * 挂库位前的存在性校验:加锁读。
     * 锁住仓库行后,并发的「删仓库」会一直等到本事务提交,
     * 于是它那次 countLocationsLocking 一定能看到刚建的库位而拒绝删除。
     *
     * 用 FOR UPDATE 而不是 FOR SHARE:后者是 MySQL 8.0 才有的语法,
     * 而本项目声明兼容 5.7+。这里锁的是一行仓库、且只在管理员建/改库位时走,
     * 独占锁的代价可以忽略。
     */
    public boolean existsForUpdate(int id) {
        List<Integer> r = jdbc.queryForList(
                "SELECT id FROM warehouse WHERE id = ? FOR UPDATE", Integer.class, id);
        return !r.isEmpty();
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
