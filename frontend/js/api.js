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
        // 后端有两套"资源不存在"约定:抛异常(上面已被 !res.ok 拦下,真 4xx),
        // 或 controller 直接 return ApiResponse.fail(404, ...) —— 后者是 HTTP 200
        // 只把 code 放在 body 里。不看 body 的 code 就会当成成功返回 null,
        // 调用方拿到 null 继续渲染,用户什么都看不到。
        if (data.code) throw new Error(data.message || ('业务错误 code=' + data.code));
        return data.data;
    },
    get(url)        { return fetch(url, { headers: this._hdr() }).then(r => this._handle(r)); },
    post(url, body) { return fetch(url, { method: 'POST', headers: this._hdr(), body: JSON.stringify(body || {}) }).then(r => this._handle(r)); },
    put(url, body)  { return fetch(url, { method: 'PUT',  headers: this._hdr(), body: JSON.stringify(body || {}) }).then(r => this._handle(r)); },
    del(url)        { return fetch(url, { method: 'DELETE', headers: this._hdr() }).then(r => this._handle(r)); },

    /** 上传 multipart。**不能套用 _hdr()**:那里写死了 Content-Type: application/json,
     *  手写的 Content-Type 会盖掉浏览器自动生成的 multipart 边界(boundary),
     *  服务端解析不出任何字段,只会报"请选择要导入的 CSV 文件"。这里只带 Authorization。 */
    upload(url, formData) {
        const h = {};
        const t = Auth.getToken();
        if (t) h['Authorization'] = 'Bearer ' + t;
        return fetch(url, { method: 'POST', headers: h, body: formData }).then(r => this._handle(r));
    },

    /** 导出选中项:POST + JSON body 传 id 列表。
     *  为什么不用 GET + query:ID 一多就超 URL 长度限制,还得操心编码。
     *  注意这里**必须**带 Content-Type: application/json(与 api.upload 相反)。 */
    async downloadPost(url, body, filename) {
        const res = await fetch(url, {
            method: 'POST',
            headers: this._hdr(),          // 内含 Authorization 与 Content-Type
            body: JSON.stringify(body || [])
        });
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
    },

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

/**
 * 设置弹窗宽度档位:'modal-wide'(880,单据详情/盘点详情)、
 * 'modal-xwide'(1120,新增单据的明细表)、null = 默认窄弹窗。
 *
 * 必须显式切换,不能只 add:宽度类是挂在**同一个** .modal 元素上的,
 * 上一个宽弹窗留下的类会跟着进下一个窄弹窗(历史上已经有一个 opener
 * 为此单独写 remove('modal-wide'),说明踩过)。
 */
function setModalWidth(cls) {
    const m = document.querySelector('.modal');
    if (!m) return;
    m.classList.remove('modal-wide', 'modal-xwide');
    if (cls) m.classList.add(cls);
}

function showModal(title, bodyHtml, onOk) {
    // 先复位成默认宽度:需要宽屏的调用方在 showModal 之后自己加档。
    // 不复位的话,开过一个宽弹窗再开窄弹窗,窄弹窗会莫名其妙地很宽。
    setModalWidth(null);
    document.getElementById('modal-title').textContent = title;
    const modalBody = document.getElementById('modal-body');
    // 重置内联样式:盘点详情弹窗为 sticky 贴底按钮行会把 paddingBottom 置 0,
    // 不重置的话下一个弹窗会继承,底部凭空少 20px 内边距
    modalBody.style.paddingBottom = '';
    // 同理清掉上一个弹窗挂的事件(如盘点导出菜单的"点空白收起"),
    // 否则残留监听会作用到后续所有弹窗上
    modalBody.onclick = null;
    modalBody.innerHTML = bodyHtml;
    document.getElementById('modal-mask').hidden = false;
    // 文案也必须每次重置:上一个弹窗可能把按钮改成"关闭"(如条码/详情),
    // 不重置的话下一个提交型弹窗会继承"关闭"文案。需要自定义文案的调用方
    // 会在 showModal 之后调 freshModalOk(自己的文案),覆盖顺序不受影响。
    const newOk = freshModalOk(onOk ? t('common.confirm') : t('common.close'));
    // onOk 可能是同步函数(返回 true/false 决定是否关闭)或返回 Promise。
    // 之前只识别同步 false,Promise 一律被吞 → 校验/网络错误时弹窗也关闭、输入丢失。
    newOk.addEventListener('click', async () => {
        if (!onOk) { closeModal(); return; }
        // 防双击:请求还没回来就不再受理点击。新增商品/供应商/仓库/用户这些确认回调
        // 都是异步 POST,连点两次就是两条记录。以前只有「新增单据」自己给按钮加了这个
        // 保护,其余弹窗都是裸的。(freshModalOk 会清掉这两个临时状态,不会带到下个弹窗。)
        if (newOk._inFlight) return;
        newOk._inFlight = true;
        newOk.disabled = true;
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
        // 保留弹窗时必须解禁,否则用户改完输入也没法再点提交
        newOk._inFlight = false;
        newOk.disabled = false;
        if (!keep) closeModal();
    });
}
function closeModal() {
    document.getElementById('modal-mask').hidden = true;
    // 盘点详情弹窗会给 modal-body 留下 paddingBottom=0 的内联样式;
    // 部分弹窗(出入库单编辑/详情等)不走 showModal 直接 innerHTML,
    // 在这里统一清掉,避免它们继承 0 底边距
    document.getElementById('modal-body').style.paddingBottom = '';
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