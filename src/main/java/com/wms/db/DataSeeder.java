package com.wms.db;

import com.wms.dao.ProductDao;
import com.wms.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 仅在 product 表为空时插入演示数据,便于首次打开页面就能看到效果。
 * 真实数据添加完成后,本播种器不会再执行。
 */
@Component
@Order(1)
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final JdbcTemplate jdbc;
    private final ProductDao productDao;
    private final UserService userService;

    @Autowired
    public DataSeeder(JdbcTemplate jdbc, ProductDao productDao, UserService userService) {
        this.jdbc = jdbc;
        this.productDao = productDao;
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

        // 1. 用户 bootstrap (env-driven,失败不致命)
        try {
            userService.bootstrapIfEmpty();
        } catch (Exception e) {
            log.warn("DataSeeder: 用户 bootstrap 失败 - {}", e.getMessage());
        }

        // 2. 演示数据
        try {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM product", Long.class);
            if (count != null && count > 0) {
                log.info("DataSeeder: product 表已有 {} 条数据,跳过演示数据", count);
                return;
            }
            log.info("DataSeeder: 首次启动,开始写入演示数据");
            seedSuppliers();
            seedProducts();
            seedProductSuppliers();
            seedLocations();
            log.info("DataSeeder: 演示数据写入完成");
        } catch (Exception e) {
            // 播种失败不影响主程序
            log.warn("DataSeeder: 播种失败 - {}", e.getMessage());
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

        // 2026-09-04: 库位字典表(新表,schema.sql 已 CREATE IF NOT EXISTS;
        // 老库若未建表,在启动时会自动建,但 seedLocations() 仅在 product 为空时跑)

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

    private void seedSuppliers() {
        jdbc.update("INSERT INTO supplier (name, contact, phone, email, address, remark) " +
                "VALUES ('得力办公旗舰店','王经理','021-66889900','sales@deli.com.cn','上海市浦东新区','主营办公文具')");
        jdbc.update("INSERT INTO supplier (name, contact, phone, email, address, remark) " +
                "VALUES ('晨光文具股份有限公司','李经理','021-67778899','b2b@mg-pen.com','上海市奉贤区','文具/笔类批量供应')");
        jdbc.update("INSERT INTO supplier (name, contact, phone, email, address, remark) " +
                "VALUES ('京东企业购','客户经理','4006-000-000','enterprise@jd.com','北京市亦庄','一站式办公采购')");
    }

    private void seedProductSuppliers() {
        // 假设已写入 product id 1..5,supplier id 1..3
        // SP-001 A4 复印纸:得力(默认)
        jdbc.update("INSERT INTO product_supplier (product_id, supplier_id, is_default) VALUES (1, 1, TRUE)");
        // SP-002 中性笔:晨光(默认)+ 得力
        jdbc.update("INSERT INTO product_supplier (product_id, supplier_id, is_default) VALUES (2, 2, TRUE)");
        jdbc.update("INSERT INTO product_supplier (product_id, supplier_id, is_default) VALUES (2, 1, FALSE)");
        // SP-003 便利贴:晨光
        jdbc.update("INSERT INTO product_supplier (product_id, supplier_id, is_default) VALUES (3, 2, TRUE)");
        // SP-004 订书机:得力
        jdbc.update("INSERT INTO product_supplier (product_id, supplier_id, is_default) VALUES (4, 1, TRUE)");
        // SP-005 矿泉水:京东企业购
        jdbc.update("INSERT INTO product_supplier (product_id, supplier_id, is_default) VALUES (5, 3, TRUE)");
    }

    private void seedLocations() {
        // 与演示商品 location 字段(A-01-03 等)对应
        jdbc.update("INSERT INTO location (code, name, remark) VALUES ('A-01-03', 'A 区 01 排 03 位', '办公耗材区')");
        jdbc.update("INSERT INTO location (code, name, remark) VALUES ('A-01-05', 'A 区 01 排 05 位', '办公耗材区')");
        jdbc.update("INSERT INTO location (code, name, remark) VALUES ('A-01-07', 'A 区 01 排 07 位', '办公耗材区')");
        jdbc.update("INSERT INTO location (code, name, remark) VALUES ('A-02-01', 'A 区 02 排 01 位', '办公文具区')");
        jdbc.update("INSERT INTO location (code, name, remark) VALUES ('B-03-01', 'B 区 03 排 01 位', '饮用水区')");
    }

    private void seedProducts() {
        jdbc.update("INSERT INTO product " +
                "(sku,name,category,unit,location,quantity,unit_price,low_stock,remark) " +
                "VALUES ('SP-001','A4 复印纸','办公耗材','包','A-01-03',50,28.50,10,'70g 500张/包')");
        jdbc.update("INSERT INTO product " +
                "(sku,name,category,unit,location,quantity,unit_price,low_stock,remark) " +
                "VALUES ('SP-002','中性笔','办公耗材','盒','A-01-05',20,15.00,10,'0.5mm 黑色 12支/盒')");
        jdbc.update("INSERT INTO product " +
                "(sku,name,category,unit,location,quantity,unit_price,low_stock,remark) " +
                "VALUES ('SP-003','便利贴','办公耗材','本','A-01-07',30,6.50,10,'76*76 黄色')");
        jdbc.update("INSERT INTO product " +
                "(sku,name,category,unit,location,quantity,unit_price,low_stock,remark) " +
                "VALUES ('SP-004','订书机','办公文具','个','A-02-01',5,22.00,5,'标准型 24/6')");
        jdbc.update("INSERT INTO product " +
                "(sku,name,category,unit,location,quantity,unit_price,low_stock,remark) " +
                "VALUES ('SP-005','矿泉水','饮用水','箱','B-03-01',20,35.00,5,'550ml*24瓶')");
    }
}