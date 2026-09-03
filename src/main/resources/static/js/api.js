/* ============================================
 *  api.js  -  REST 调用 + Toast/Modal 辅助
 * ============================================ */

const api = {
    _hdr() {
        const t = Auth.getToken();
        const h = { 'Content-Type': 'application/json' };
        if (t) h['Authorization'] = 'Bearer ' + t;
        return h;
    },
    async _handle(res) {
        if (res.status === 401) {
            Auth.clearToken();
            // 避免在登录页重复跳
            if (!location.pathname.endsWith('/login.html')) {
                location.href = '/login.html';
            }
            throw new Error('未登录');
        }
        let data;
        try { data = await res.json(); }
        catch (e) { data = { code: res.status, message: '响应解析失败' }; }
        if (!res.ok) throw new Error(data.message || ('HTTP ' + res.status));
        return data.data;
    },
    get(url)        { return fetch(url, { headers: this._hdr() }).then(r => this._handle(r)); },
    post(url, body) { return fetch(url, { method: 'POST', headers: this._hdr(), body: JSON.stringify(body || {}) }).then(r => this._handle(r)); },
    put(url, body)  { return fetch(url, { method: 'PUT',  headers: this._hdr(), body: JSON.stringify(body || {}) }).then(r => this._handle(r)); },
    del(url)        { return fetch(url, { method: 'DELETE', headers: this._hdr() }).then(r => this._handle(r)); }
};

/* ---------- Toast ---------- */
let toastTimer;
function toast(message, type) {
    const el = document.getElementById('toast');
    el.textContent = message;
    el.className = 'toast' + (type ? ' ' + type : '');
    el.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { el.hidden = true; }, 2400);
}

/* ---------- Modal ---------- */
function showModal(title, bodyHtml, onOk) {
    document.getElementById('modal-title').textContent = title;
    document.getElementById('modal-body').innerHTML = bodyHtml;
    document.getElementById('modal-mask').hidden = false;
    const ok = document.getElementById('modal-ok');
    const newOk = ok.cloneNode(true);
    ok.parentNode.replaceChild(newOk, ok);
    newOk.addEventListener('click', () => {
        if (onOk && onOk() === false) return;  // 返回 false 表示不关闭
        closeModal();
    });
}
function closeModal() {
    document.getElementById('modal-mask').hidden = true;
}

function confirmModal(message, onOk) {
    showModal('确认', `<p style="margin:0;font-size:14px;">${escapeHtml(message)}</p>`, onOk);
}

/* ---------- 工具 ---------- */
function escapeHtml(s) {
    if (s === null || s === undefined) return '';
    return String(s)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function formatMoney(n) {
    if (n === null || n === undefined) return '0.00';
    return Number(n).toFixed(2);
}

function formatDate(s) {
    if (!s) return '';
    // 服务端 Jackson 用 yyyy-MM-dd HH:mm:ss 输出,已是本地时区字符串
    return s.replace('T', ' ');
}