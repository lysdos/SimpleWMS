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
    del(url)        { return fetch(url, { method: 'DELETE', headers: this._hdr() }).then(r => this._handle(r)); },

    /** 导出 CSV / 二进制:用 fetch 带 Authorization 头,再以 Blob 触发下载。
     *  直接 <a href> 或 window.open 顶层导航不会带 Authorization,会 401。 */
    async download(url, filename) {
        const res = await fetch(url, { headers: this._hdr() });
        if (res.status === 401) {
            Auth.clearToken();
            if (!location.pathname.endsWith('/login.html')) location.href = '/login.html';
            throw new Error('未登录');
        }
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const blob = await res.blob();
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob);
        a.download = filename || url.split('/').pop() || 'download';
        document.body.appendChild(a);
        a.click();
        setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 200);
    }
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

/**
 * 取一个「干净」的 #modal-ok 副本替换原按钮,并返回新按钮,调用方再挂自己的 click。
 * 为什么需要:各弹窗都靠 cloneNode(true) 重置按钮文案/监听,而 cloneNode 会
 * **连同 disabled 属性一起复制**。只要有一次把按钮置灰后忘了复位(如提交成功后),
 * 之后每个弹窗的「确定/关闭/提交」都会继承置灰,直到刷新页面才恢复。
 * 统一在这里清掉临时状态,杜绝这类跨弹窗的状态泄漏。
 * @param {string} [text] 新按钮文案;不传则保留原文案
 */
function freshModalOk(text) {
    const ok = document.getElementById('modal-ok');
    const fresh = ok.cloneNode(true);
    fresh.disabled = false;     // cloneNode 会复制 disabled,必须显式清掉
    fresh._inFlight = false;    // 防双击标记,同样不能带到下一个弹窗
    if (text !== undefined) fresh.textContent = text;
    ok.parentNode.replaceChild(fresh, ok);
    return fresh;
}

function showModal(title, bodyHtml, onOk) {
    document.getElementById('modal-title').textContent = title;
    document.getElementById('modal-body').innerHTML = bodyHtml;
    document.getElementById('modal-mask').hidden = false;
    const newOk = freshModalOk();
    // onOk 可能是同步函数(返回 true/false 决定是否关闭)或返回 Promise。
    // 之前只识别同步 false,Promise 一律被吞 → 校验/网络错误时弹窗也关闭、输入丢失。
    newOk.addEventListener('click', async () => {
        if (!onOk) { closeModal(); return; }
        let keep = false;
        let resolved = false;
        try {
            const ret = onOk();
            if (ret && typeof ret.then === 'function') {
                resolved = true;
                keep = await ret.then(v => v === false, () => true);  // 抛错时 keep=true 不关闭
            } else {
                keep = ret === false;
            }
        } catch (e) {
            if (!resolved) toast(e.message || t('err.network'), 'error');
            keep = true;   // 出错保留弹窗,展示后续错误
        }
        if (!keep) closeModal();
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