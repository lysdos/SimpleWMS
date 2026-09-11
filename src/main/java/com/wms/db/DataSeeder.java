package com.wms.db;

import com.wms.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时的库初始化:只做 schema 幂等迁移 + 首个 admin 账号 bootstrap。
 * 不写入任何演示数据 —— 商品 / 供应商 / 库位一律由使用方通过界面自行建立。
 * 需要演示数据时,手动执行 tools/seed.sql 或 tools/seed_demo_data.py。
 */
@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JdbcTemplate jdbc;
    private final UserService userService;

    @Autowired
    public DataSeeder(JdbcTemplate jdbc, UserService userService) {
        this.jdbc = jdbc;
        this.userService = userService;
    }

    @Override
    public void run(String... args) {
        // 0. schema 幂等迁移(老库可能没有 order_id / 入库单表)
        try {
            migrate();
        } catch (Exception e) {
            log.warn("DataSeeder: schema 迁移失败(可能新装,无需迁移) - {}", e.getMessage());
        }

        // 1. 首个 admin 账号 bootstrap(env-driven,失败不致命)
        try {
            userService.bootstrapIfEmpty();
        } catch (Exception e) {
            log.warn("DataSeeder: 用户 bootstrap 失败 - {}", e.getMessage());
        }
    }

    /**
     * 老库兼容:为 inventory_log 补 order_id 列(已存在则忽略),
     * 并补 idx_log_order 索引(H2 在同一脚本里 CREATE TABLE 后立刻
     * CREATE INDEX 引用新列会失败,挪到这里保证顺序)。
     */
    private void migrate() {
        tryAddColumn("inventory_log", "order_id", "INT DEFAULT NULL");
        tryAddIndex("idx_log_order", "inventory_log", "order_id");
        // 2026-09-03: 入库单明细行级 supplier(支持同一单据多供应商)
        tryAddColumn("inbound_order_item", "supplier", "VARCHAR(128) DEFAULT NULL");
        // 注:supplier / product_supplier 表在 schema.sql 里 CREATE IF NOT EXISTS
        // 全新装库会自动建,老库(已有 supplier 表但表结构升级)靠 schema.sql 同样幂等

        // 2026-09-04: 库位字典表(新表,schema.sql 已 CREATE IF NOT EXISTS 自动建)

        // 2026-09-04: 入库/出库单明细加 location(冗余,记录当时的库位,商品换库位后历史单据不变)
        tryAddColumn("inbound_order_item",  "location",  "VARCHAR(32) DEFAULT NULL");
        tryAddColumn("outbound_order_item", "location",  "VARCHAR(32) DEFAULT NULL");
        // 2026-09-04: 库存单元表(新表,schema.sql 已 CREATE IF NOT EXISTS;
        // 老库若没建表会自动建,但不会回填历史数据,历史出入库单只展示,不进库存管理)
        // 2026-09-04: 库存单元加 created_by 操作员(谁创建的实物单元)
        tryAddColumn("inventory_item", "created_by", "VARCHAR(64) DEFAULT NULL");
        // 2026-09-04: 库存盘点单 + 盘点明细(scheme.sql 已 CREATE IF NOT EXISTS;老库无表会自动建)
    }

    private void tryAddIndex(String indexName, String table, String col) {
        Integer cnt;
        try {
            cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.indexes " +
                    "WHERE table_name = ? AND index_name = ?",
                    Integer.class, table.toUpperCase(), indexName.toUpperCase());
        } catch (Exception e) {
            cnt = null;
        }
        if (cnt != null && cnt > 0) return;
        try {
            jdbc.execute("CREATE INDEX " + indexName + " ON " + table + "(" + col + ")");
            log.info("DataSeeder: CREATE INDEX {} 成功", indexName);
        } catch (Exception e) {
            // 静默:多半是已存在
        }
    }

    private void tryAddColumn(String table, String col, String ddl) {
        // 先查列是否存在(双库都兼容)
        Integer cnt;
        try {
            cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_name = ? AND column_name = ?",
                    Integer.class, table.toUpperCase(), col.toUpperCase());
        } catch (Exception e) {
            // 退路:直接尝试 ALTER,失败就跳过
            cnt = null;
        }
        if (cnt != null && cnt > 0) return;
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + ddl);
            log.info("DataSeeder: ALTER TABLE {} ADD COLUMN {} 成功", table, col);
        } catch (Exception e) {
            // 静默:多半是已存在/不支持
        }
    }
}
