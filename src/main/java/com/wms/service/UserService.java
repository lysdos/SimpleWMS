package com.wms.service;

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
     *  未设置 admin.password 时,降级使用开发默认密码 'admin123',并打 WARN 提醒生产环境务必覆盖。 */
    @Transactional
    public void bootstrapIfEmpty() {
        if (userDao.count() > 0) return;
        String pwd = (adminPassword == null || adminPassword.isBlank())
                ? "admin123" : adminPassword;
        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("[UserService] 未设置 admin.password,使用开发默认密码 'admin123'。"
                    + "生产环境请通过环境变量 admin.password=... 覆盖,并立即登录后修改。");
        }
        AppUser u = new AppUser();
        u.setUsername(adminUsername);
        u.setPasswordHash(passwordService.hash(pwd));
        u.setRole("ADMIN");
        u.setEnabled(true);
        u.setMustChangePw(true);
        userDao.insert(u);
        log.info("[UserService] 已创建首账号 username='{}' role=ADMIN mustChangePw=true", adminUsername);
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
        return userDao.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    public List<AppUser> list() {
        return userDao.listAll();
    }

    @Transactional
    public AppUser create(String username, String rawPassword, String role) {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("用户名不能为空");
        if (rawPassword == null || rawPassword.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
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
        if (newRaw == null || newRaw.length() < 6) throw new IllegalArgumentException("密码至少 6 位");
        userDao.updatePassword(id, passwordService.hash(newRaw), true);
    }

    @Transactional
    public void changeOwnPassword(int id, String oldRaw, String newRaw) {
        AppUser u = userDao.findById(id).orElseThrow(() -> new IllegalArgumentException("用户不存在"));
        if (!passwordService.verify(oldRaw, u.getPasswordHash())) {
            throw new IllegalArgumentException("原密码错误");
        }
        if (newRaw == null || newRaw.length() < 6) throw new IllegalArgumentException("新密码至少 6 位");
        userDao.updatePassword(id, passwordService.hash(newRaw), false);
    }

    @Transactional
    public void setEnabled(int id, boolean enabled) {
        if (userDao.findById(id).isEmpty()) throw new IllegalArgumentException("用户不存在");
        userDao.setEnabled(id, enabled);
    }
}