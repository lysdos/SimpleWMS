const TOKEN_KEY = 'wms.token';

const Auth = {
    getToken() { return localStorage.getItem(TOKEN_KEY); },
    setToken(t) { localStorage.setItem(TOKEN_KEY, t); },
    clearToken() { localStorage.removeItem(TOKEN_KEY); },

    async login(username, password) {
        const res = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, password })
        });
        let data;
        try { data = await res.json(); } catch (e) { data = {}; }
        if (res.status === 401) throw new Error('用户名或密码错误');
        if (res.status === 403) throw new Error('账号已禁用,请联系管理员');
        if (!res.ok) throw new Error(data.message || '登录失败');
        this.setToken(data.data.token);
        if (data.data.mustChangePw) {
            location.href = '/change-password.html';
        } else {
            location.href = '/';
        }
    },

    async logout() {
        const t = this.getToken();
        // 通知后端只是礼节(JWT 无状态,后端就回个 200):它失败不该挡住登出本身。
        // 以前这里是裸 await —— 断网/服务没起时 fetch 抛错,后面的清 token 与跳转全被跳过,
        // 用户点「退出」反而退不出去。
        try {
            if (t) {
                await fetch('/api/auth/logout', { method: 'POST', headers: { 'Authorization': 'Bearer ' + t } });
            }
        } catch (e) { /* 忽略:本地清干净才是登出的实质 */ }
        this.clearToken();
        location.href = '/login.html';
    },

    async changeOwnPassword(oldPw, newPassword) {
        const t = this.getToken();
        const res = await fetch('/api/auth/me/change-password', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + t },
            body: JSON.stringify({ oldPassword: oldPw, newPassword })
        });
        let j;
        try { j = await res.json(); } catch (e) { j = {}; }
        if (!res.ok) throw new Error(j.message || '修改失败');
    }
};