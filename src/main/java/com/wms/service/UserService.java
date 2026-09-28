package com.wms.service;

import com.wms.controller.NotFoundException;
import com.wms.dao.UserDao;
import com.wms.model.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserDao userDao;
    private final PasswordService passwordService;

    @Value("${admin.username:admin}")
    private String adminUsername;

    @Value("${admin.password:}")
    private String adminPassword;

    @Autowired
    public UserService(UserDao userDao, PasswordService passwordService) {
        this.userDao = userDao;
        this.passwordService = passwordService;
    }

    /** 启动时若 app_user 为空,用 env 创建首账号 (admin, ADMIN, mustChangePw=true)。
     *  强制要求 env 中显式提供 admin.password(已知默认 'admin123' / 'change-me-now'
     *  一律拒绝),避免新部署撞到公开可猜口令。 */
    @Transactional
    public void bootstrapIfEmpty() {
        if (userDao.count() > 0) return;
        if (adminPassword == null || adminPassword.isBlank()) {
            throw new IllegalStateException(
                "首次启动缺少 admin.password 配置。请在 .env 里设置 ADMIN_PASSWORD=<强口令> 后再启动。");
        }
        if (isWeakKnownDefault(adminPassword)) {
            throw new IllegalStateException(
                "admin.password 命中已知弱默认口令;请改用强口令后再启动。");
        }
        AppUser u = new AppUser();
        u.setUsername(adminUsername);
        u.setPasswordHash(passwordService.hash(adminPassword));
        u.setRole("ADMIN");
        u.setEnabled(true);
        u.setMustChangePw(true);
        userDao.insert(u);
        log.info("[UserService] 已创建首账号 username='{}' role=ADMIN mustChangePw=true", adminUsername);
    }

    private static boolean isWeakKnownDefault(String pwd) {
        // 不可作为初始口令(下发了也拒绝)
        if (pwd.length() < 8) return true;
        String lower = pwd.toLowerCase();
        return lower.equals("admin123") || lower.equals("change-me-now") || lower.equals("password");
    }

    public AppUser login(String username, String rawPassword) {
        AppUser u = userDao.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("用户名或密码错误"));
        if (Boolean.FALSE.equals(u.getEnabled())) {
            throw new IllegalStateException("账号已禁用");
        }
        if (!passwordService.verify(rawPassword, u.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }
        return u;
    }

    public AppUser get(int id) {
        return userDao.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
    }

    public List<AppUser> list() {
        return userDao.listAll();
    }

    public List<AppUser> findUsers(String keyword, String role, String status, String beginDate, String endDate) {
        return userDao.findUsers(keyword, role, status, beginDate, endDate);
    }

    @Transactional
    public AppUser create(String username, String rawPassword, String role) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("用户名不能为空");
        if (rawPassword == null || rawPassword.length() < 8) throw new IllegalArgumentException("密码至少 8 位");
        if (!"ADMIN".equals(role) && !"OPERATOR".equals(role)) throw new IllegalArgumentException("角色必须为 ADMIN / OPERATOR");
        if (userDao.findByUsername(username).isPresent()) throw new IllegalArgumentException("用户名已存在");
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setPasswordHash(passwordService.hash(rawPassword));
        u.setRole(role);
        u.setEnabled(true);
        u.setMustChangePw(true);
        try {
            int id = userDao.insert(u);
            u.setId(id);
            return u;
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("用户名已存在");
        }
    }

    @Transactional
    public void resetPassword(int id, String newRaw) {
        if (newRaw == null || newRaw.length() < 8) throw new IllegalArgumentException("密码至少 8 位");
        // 不校验存在性的话,对不存在的 id 会静默更新 0 行并返回成功,
        // 前端弹"重置成功"但实际什么都没做
        if (userDao.findById(id).isEmpty()) throw new NotFoundException("用户不存在");
        userDao.updatePassword(id, passwordService.hash(newRaw), true);
    }

    @Transactional
    public AppUser updateRole(int id, String role) {
        if (!"ADMIN".equals(role) && !"OPERATOR".equals(role)) throw new IllegalArgumentException("角色必须为 ADMIN / OPERATOR");
        AppUser u = userDao.findById(id).orElseThrow(() -> new NotFoundException("用户不存在"));
        u.setRole(role);
        userDao.updateRole(id, role);
        return u;
    }

    @Transactional
    public void changeOwnPassword(int id, String oldRaw, String newRaw) {
        // 这里刻意**保持** IllegalArgumentException:AuthController.changeOwn 专门 catch 它
        // 返回 400("改自己密码"这类输入校验错误)。改成 NotFoundException 会漏出那个 catch,
        // 变成 404 —— 全项目唯一一处"不改"的存在性校验,别顺手统一。
        AppUser u = userDao.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordService.verify(oldRaw, u.getPasswordHash())) {
            throw new IllegalArgumentException("原密码错误");
        }
        if (newRaw == null || newRaw.length() < 8) throw new IllegalArgumentException("新密码至少 8 位");
        userDao.updatePassword(id, passwordService.hash(newRaw), false);
    }

    @Transactional
    public void setEnabled(int id, boolean enabled) {
        if (userDao.findById(id).isEmpty()) throw new NotFoundException("用户不存在");
        userDao.setEnabled(id, enabled);
    }

    /**
     * 删除用户:
     *   1) 不删除用户所产生的业务记录(inventory_log / inbound_order / outbound_order
     *      中的 operator 都是 VARCHAR,不是外键,天然不级联)
     *   2) 不能删除自己
     *   3) 不能删除最后一名 ADMIN(防止系统锁死)
     *   4) 目标用户不存在 → 报错
     */
    @Transactional
    public void delete(int id, int currentUserId) {
        if (id == currentUserId) {
            throw new IllegalArgumentException("不能删除当前登录的用户");
        }
        AppUser target = userDao.findById(id).orElseThrow(() ->
                new NotFoundException("用户不存在, id=" + id));
        if ("ADMIN".equals(target.getRole())) {
            long adminCount = userDao.listAll().stream()
                    .filter(u -> "ADMIN".equals(u.getRole()))
                    .count();
            if (adminCount <= 1) {
                throw new IllegalArgumentException("系统至少保留一名管理员,无法删除");
            }
        }
        userDao.delete(id);
    }
}