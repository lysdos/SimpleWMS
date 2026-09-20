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
        if (t) {
            await fetch('/api/auth/logout', { method: 'POST', headers: { 'Authorization': 'Bearer ' + t } });
        }
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