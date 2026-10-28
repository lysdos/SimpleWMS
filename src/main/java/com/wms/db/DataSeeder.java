package com.wms.db;

import com.wms.model.Location;
import com.wms.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

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
     * 并补 idx_log_order 索引(建列与建索引分两步,保证索引引用的列已存在)。
     */
    private void migrate() {
        tryAddColumn("inventory_log", "order_id", "INT DEFAULT NULL");
        tryAddIndex("idx_log_order", "inventory_log", "order_id");
        // 2026-09-03: 入库单明细行级 supplier(支持同一单据多供应商)
        tryAddColumn("inbound_order_item", "supplier", "VARCHAR(128) DEFAULT NULL");
        // 注:supplier / product_supplier 表在 mysql-schema.sql 里 CREATE IF NOT EXISTS
        // 全新装库会自动建,老库(已有 supplier 表但表结构升级)靠 mysql-schema.sql 同样幂等

        // 2026-09-04: 库位字典表(新表,mysql-schema.sql 已 CREATE IF NOT EXISTS 自动建)

        // 2026-09-04: 入库/出库单明细加 location(冗余,记录当时的库位,商品换库位后历史单据不变)
        tryAddColumn("inbound_order_item",  "location",  "VARCHAR(32) DEFAULT NULL");
        tryAddColumn("outbound_order_item", "location",  "VARCHAR(32) DEFAULT NULL");
        // 2026-09-04: 库存单元表(新表,mysql-schema.sql 已 CREATE IF NOT EXISTS;
        // 老库若没建表会自动建,但不会回填历史数据,历史出入库单只展示,不进库存管理)
        // 2026-09-04: 库存单元加 created_by 操作员(谁创建的实物单元)
        tryAddColumn("inventory_item", "created_by", "VARCHAR(64) DEFAULT NULL");
        // 2026-09-04: 库存盘点单 + 盘点明细(mysql-schema.sql 已 CREATE IF NOT EXISTS;老库无表会自动建)

        // 2026-09-18: 仓库(库位的上层分组)。新表+新列,老库不会自动拿到,
        // 所以这里显式补;补列后**仅这一次**把原有库位归到默认仓库(见下)。
        boolean warehouseIdJustAdded = tryAddColumn("location", "warehouse_id", "INT DEFAULT NULL");
        tryAddIndex("idx_location_warehouse", "location", "warehouse_id");
        ensureDefaultWarehouse(warehouseIdJustAdded);

        // 2026-09-23: 盘点单记下范围所属仓库,供扫码时挡住来自其他仓库的码
        //(以前按仓盘点会静默吸收误扫的外仓货,记成自己的盘盈)
        tryAddColumn("stock_check", "warehouse_id", "INT DEFAULT NULL");

        // 2026-09-23: location.code 补唯一索引。
        // 此前只有 PRIMARY KEY,重复编码能建出来;而库存单元和商品的默认库位
        // 都是按 code 裸字符串引用的,一码两行会让库存列表的 LEFT JOIN 把每个
        // 单元返回两行(列表与 total 对不上),并让按仓盘点把别的仓库的货也算进来。
        ensureLocationCodeUnique();

        // 2026-10-28: supplier.name 补唯一索引。
        // 此前只有 PRIMARY KEY,同名能建出来,而 SupplierService.create 里的
        // DuplicateKeyException 分支因此是死代码。名称在下单弹窗里是下拉框的 value,
        // 重名会让两个选项长得一模一样、选谁都写同一个名字。
        ensureSupplierNameUnique();
    }

    /**
     * 给 supplier.name 补唯一索引(幂等),写法照 {@link #ensureLocationCodeUnique()}。
     *
     * 先体检再 ALTER:老库可能有历史重名,直接 ALTER 会失败并让应用启动即崩。
     * 有重复就报错日志 + 跳过,下次启动再试。
     */
    private void ensureSupplierNameUnique() {
        Integer cnt;
        try {
            cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = 'supplier' " +
                    "AND index_name = 'uk_supplier_name'", Integer.class);
        } catch (Exception e) {
            cnt = null;
        }
        if (cnt != null && cnt > 0) return;

        List<String> dups;
        try {
            dups = jdbc.queryForList(
                    "SELECT name FROM supplier GROUP BY name HAVING COUNT(*) > 1", String.class);
        } catch (Exception e) {
            log.warn("DataSeeder: 检查供应商重复名称失败 - {}", e.getMessage());
            return;
        }
        if (!dups.isEmpty()) {
            log.error("DataSeeder: supplier 表存在重复的供应商名称 {} —— 唯一索引未创建。" +
                    "请先在供应商管理里合并这些名称,下次启动会自动补上。", dups);
            return;
        }
        try {
            jdbc.execute("ALTER TABLE supplier ADD UNIQUE KEY uk_supplier_name (name)");
            log.info("DataSeeder: 已为 supplier.name 创建唯一索引 uk_supplier_name");
        } catch (Exception e) {
            log.warn("DataSeeder: 创建 uk_supplier_name 失败 - {}", e.getMessage());
        }
    }

    /**
     * 给 location.code 补唯一索引(幂等)。
     *
     * 老库可能已经有重复编码 —— 那种情况下 ALTER 会直接失败,所以先体检:
     * 有重复就**报错日志 + 跳过**,让人先合并,而不是让应用启动即崩。
     * 下次启动会再试一次。
     */
    private void ensureLocationCodeUnique() {
        Integer cnt;
        try {
            cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = 'location' " +
                    "AND index_name = 'uk_location_code'", Integer.class);
        } catch (Exception e) {
            cnt = null;
        }
        if (cnt != null && cnt > 0) return;

        List<String> dups;
        try {
            dups = jdbc.queryForList(
                    "SELECT code FROM location GROUP BY code HAVING COUNT(*) > 1", String.class);
        } catch (Exception e) {
            log.warn("DataSeeder: 检查库位重复编码失败 - {}", e.getMessage());
            return;
        }
        if (!dups.isEmpty()) {
            log.error("DataSeeder: location 表存在重复的库位编码 {} —— 唯一索引未创建。" +
                    "请先在库位管理里合并这些编码(把库存调拨到保留的那一个),下次启动会自动补上。", dups);
            return;
        }
        try {
            jdbc.execute("ALTER TABLE location ADD UNIQUE KEY uk_location_code (code)");
            log.info("DataSeeder: 已为 location.code 创建唯一索引 uk_location_code");
        } catch (Exception e) {
            log.warn("DataSeeder: 创建 uk_location_code 失败 - {}", e.getMessage());
        }
    }

    /**
     * 仓库体系的自举,幂等:
     *  1) 建 warehouse 表(老库缺表时);
     *  2) 确保存在一个「默认仓库」——多仓库上线后原有库位要有归属,
     *     否则库位管理页会一片"未分配";
     *  3) 确保库位「默认库位」真实存在并挂在该仓库下。
     *     (这个 code 一直被 InboundService/OutboundService/ProductDao 当
     *      "商品没填库位"时的兜底值用,但它此前并不在 location 表里,
     *      是个查不到的幽灵码;借这次把它变成真实库位。)
     *  4) 只有 warehouse_id 列**刚被加出来**的那一次,才把存量库位
     *     (warehouse_id IS NULL)全部归到默认仓库。之后启动不再回填,
     *     避免把用户刻意留空的"未分配"又塞回去。
     */
    private void ensureDefaultWarehouse(boolean warehouseIdJustAdded) {
        try {
            jdbc.execute(
                "CREATE TABLE IF NOT EXISTS warehouse (" +
                "  id INT NOT NULL AUTO_INCREMENT," +
                "  code VARCHAR(32) NOT NULL," +
                "  name VARCHAR(64) NOT NULL," +
                "  remark VARCHAR(255) DEFAULT NULL," +
                "  enabled TINYINT(1) NOT NULL DEFAULT 1," +
                "  created_by VARCHAR(64) DEFAULT NULL," +
                "  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP," +
                "  updated_by VARCHAR(64) DEFAULT NULL," +
                "  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
                "  PRIMARY KEY (id)," +
                "  UNIQUE KEY uk_warehouse_code (code)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci");
        } catch (Exception e) {
            log.warn("DataSeeder: 建 warehouse 表失败 - {}", e.getMessage());
            return;
        }

        Integer wid = null;
        try {
            Integer exist = queryIntOrNull("SELECT id FROM warehouse WHERE code = ?", "WH-DEFAULT");
            if (exist != null) {
                wid = exist;
            } else {
                // 只在一条仓库都没有时才自建默认仓库;用户已自建过仓库就不打扰。
                // 注意这里**不能 return** —— 下面的兜底库位无论有没有默认仓库都必须存在,
                // 否则「默认库位」会一直是个查不到的幽灵码(见 ensureFallbackLocation)。
                Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM warehouse", Integer.class);
                if (total == null || total == 0) {
                    jdbc.update("INSERT INTO warehouse (code, name, remark, enabled, created_by) " +
                                "VALUES (?,?,?,?,?)",
                            "WH-DEFAULT", "默认仓库", "系统初始化自动创建,可改名或停用", Boolean.TRUE, "system");
                    Integer created = queryIntOrNull("SELECT id FROM warehouse WHERE code = ?", "WH-DEFAULT");
                    if (created == null) {
                        log.warn("DataSeeder: 默认仓库插入后查不到,跳过初始化");
                    } else {
                        wid = created;
                        log.info("DataSeeder: 已创建默认仓库(WH-DEFAULT),id={}", wid);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("DataSeeder: 初始化默认仓库失败 - {}", e.getMessage());
        }

        if (warehouseIdJustAdded && wid != null) {
            try {
                int n = jdbc.update("UPDATE location SET warehouse_id = ? WHERE warehouse_id IS NULL", wid);
                if (n > 0) log.info("DataSeeder: 已将 {} 个存量库位归入默认仓库", n);
            } catch (Exception e) {
                log.warn("DataSeeder: 存量库位归类失败 - {}", e.getMessage());
            }
        }

        ensureFallbackLocation(wid);
    }

    /**
     * 确保兜底库位「默认库位」真实存在(每次启动都检查,幂等)。
     *
     * 这个 code 一直被 InboundService.defaultLocationOf / ProductDao 当"商品没填库位"
     * 时的兜底值写进 inventory_item.location_code —— 如果 location 表里没有它,
     * 那批单元就成了"查不到所属仓库"的幽灵:能出库、计入总量,却在任何按仓筛选
     * 和按仓盘点里都看不见。
     *
     * 以前只在"首次初始化"那一次建,于是用户先自建过仓库、或从备份恢复的库,
     * 兜底库位永远不会被补上。现在改为每次启动都确保;删也删不掉(见
     * LocationService.delete 的保护)。
     *
     * wid 为空(用户自建了仓库、没有 WH-DEFAULT)时挂在"未分配"下 ——
     * 不硬塞进某个真实仓库,免得凭空改变那个仓库的盘点范围。
     */
    private void ensureFallbackLocation(Integer wid) {
        try {
            Integer cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM location WHERE code = ?", Integer.class,
                    Location.FALLBACK_CODE);
            if (cnt != null && cnt > 0) return;
            if (wid == null) {
                jdbc.update("INSERT INTO location (code, name, remark, enabled, created_by) " +
                            "VALUES (?,?,?,?,?)",
                        Location.FALLBACK_CODE, Location.FALLBACK_CODE,
                        "商品未设置库位时的兜底库位(系统初始化自动创建)", Boolean.TRUE, "system");
            } else {
                jdbc.update("INSERT INTO location (code, name, warehouse_id, remark, enabled, created_by) " +
                            "VALUES (?,?,?,?,?,?)",
                        Location.FALLBACK_CODE, Location.FALLBACK_CODE, wid,
                        "商品未设置库位时的兜底库位(系统初始化自动创建)", Boolean.TRUE, "system");
            }
            log.info("DataSeeder: 已创建兜底库位「默认库位」");
        } catch (Exception e) {
            log.warn("DataSeeder: 初始化兜底库位失败 - {}", e.getMessage());
        }
    }

    private void tryAddIndex(String indexName, String table, String col) {
        Integer cnt;
        try {
            // 注意 1:必须限定 table_schema = DATABASE()。information_schema 是**全实例**视图,
            //   不限定的话,同一 MySQL 上别的库有同名表 + 同名列,预检查就会命中,
            //   于是本库的 ALTER 被跳过 —— 迁移静默失效。
            // 注意 2:本库 information_schema 的比较是大小写敏感的,参数必须与
            //   schema 里的实际拼写(全小写)一致,否则预检查恒为 0、退化成"盲试"
            cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.statistics " +
                    "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                    Integer.class, table, indexName);
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

    /**
     * 取单个整数,查不到返回 null。
     *
     * ⚠️ 不能用 queryForObject:它在**零行**时抛 EmptyResultDataAccessException
     * ("Incorrect result size: expected 1, actual 0"),而不是返回 null ——
     * 曾因此在"还没有默认仓库"的库上直接中断整个自举流程。
     */
    private Integer queryIntOrNull(String sql, Object... args) {
        List<Integer> list = jdbc.query(sql, (rs, n) -> rs.getInt(1), args);
        return list.isEmpty() ? null : list.get(0);
    }

    /** @return true = 本次真的加上了这一列(用于"只迁移一次"的一次性回填判断) */
    private boolean tryAddColumn(String table, String col, String ddl) {
        // 先查列是否存在(参数用小写原始拼写,原因见 tryAddIndex 注释)
        Integer cnt;
        try {
            cnt = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
                    Integer.class, table, col);
        } catch (Exception e) {
            // 退路:直接尝试 ALTER,失败就跳过
            cnt = null;
        }
        if (cnt != null && cnt > 0) return false;
        try {
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + ddl);
            log.info("DataSeeder: ALTER TABLE {} ADD COLUMN {} 成功", table, col);
            return true;
        } catch (Exception e) {
            // 静默:多半是已存在/不支持
            return false;
        }
    }
}
