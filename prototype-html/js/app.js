/* ===========================================================
 * SimpleWMS PDA · HTML 设计稿公共脚本
 * 处理:Tab 切换、模拟扫码弹窗、Toast、操作日志追加等
 * =========================================================== */

const App = {
    /* ---------- Toast ---------- */
    toast(msg) {
        const screen = document.querySelector('.pda-screen') || document.body;
        let t = screen.querySelector('.toast');
        if (!t) {
            t = document.createElement('div');
            t.className = 'toast';
            screen.appendChild(t);
        }
        t.textContent = msg;
        t.classList.add('show');
        clearTimeout(this._toastT);
        this._toastT = setTimeout(() => t.classList.remove('show'), 1800);
    },

    /* ---------- 子 Tab 切换(待办/已完成) ---------- */
    bindSubTabs(scope) {
        const root = scope || document;
        root.querySelectorAll('.subtabs').forEach(bar => {
            const tabs = bar.querySelectorAll('.tab');
            tabs.forEach((tab, i) => {
                tab.addEventListener('click', () => {
                    tabs.forEach(t => t.classList.remove('active'));
                    tab.classList.add('active');
                    const target = bar.dataset.target;
                    if (target) {
                        const panes = document.querySelectorAll(`[data-pane-group="${target}"]`);
                        panes.forEach(p => p.style.display = 'none');
                        const pane = document.querySelector(`[data-pane-group="${target}"][data-pane-index="${i}"]`);
                        if (pane) pane.style.display = '';
                    } else {
                        // 同级隐藏/显示(没有 data-target 时退回到兄弟节点方案)
                    }
                });
            });
        });
    },

    /* ---------- 底部 Tab Bar 选中 ---------- */
    bindBottomNav(activeKey) {
        document.querySelectorAll('.tabbar .tab').forEach(t => {
            if (t.dataset.tab === activeKey) t.classList.add('active');
            else t.classList.remove('active');
        });
    },

    /* ---------- 模拟扫码对话框 ---------- */
    showScanDialog(onConfirm) {
        const preset = [
            '6901234567001  (蓝牙条码扫描枪)',
            '6901234567002  (热敏标签打印机)',
            '6901234567003  (工业 PDA 终端)',
            '6901234567004  (不干胶标签 50x30)',
            '6901234567005  (碳带 110mm x 300m)',
            '6901234567006  (周转箱 600x400)',
            '6901234567007  (防静电手套)'
        ];

        const screen = document.querySelector('.pda-screen');
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.innerHTML = `
            <div class="modal" style="max-width:300px;">
                <div class="head">扫码结果 (原型模拟)</div>
                <div class="body">
                    <div class="caption mb-8">生产环境将使用 CameraX + ML Kit 识别真实条码,原型中可手动输入或选预置条码。</div>
                    <div class="caption">手动输入条码</div>
                    <input class="input mt-4" id="__scan_input" placeholder="如 6901234567001" />
                    <div class="caption mt-12">或选预置条码</div>
                    <select class="input mt-4" id="__scan_preset">
                        <option value="">-- 选择 --</option>
                        ${preset.map(p => `<option value="${p.split(' ')[0]}">${p}</option>`).join('')}
                    </select>
                </div>
                <div class="actions">
                    <button data-act="cancel">取消</button>
                    <button class="primary" data-act="ok">确认</button>
                </div>
            </div>
        `;
        screen.appendChild(mask);
        mask.addEventListener('click', e => {
            if (e.target === mask) { mask.remove(); }
        });
        mask.querySelector('[data-act="cancel"]').onclick = () => mask.remove();
        mask.querySelector('[data-act="ok"]').onclick = () => {
            let code = mask.querySelector('#__scan_input').value.trim();
            if (!code) code = mask.querySelector('#__scan_preset').value;
            if (!code) { App.toast('请输入或选择条码'); return; }
            mask.remove();
            onConfirm && onConfirm(code);
        };
    },

    /* ---------- 通用确认框 ---------- */
    confirm(title, message, onOk) {
        const screen = document.querySelector('.pda-screen');
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.innerHTML = `
            <div class="modal" style="max-width:280px;">
                <div class="head">${title}</div>
                <div class="body caption">${message}</div>
                <div class="actions">
                    <button data-act="cancel">取消</button>
                    <button class="primary" data-act="ok">确认</button>
                </div>
            </div>
        `;
        screen.appendChild(mask);
        mask.addEventListener('click', e => { if (e.target === mask) mask.remove(); });
        mask.querySelector('[data-act="cancel"]').onclick = () => mask.remove();
        mask.querySelector('[data-act="ok"]').onclick = () => { mask.remove(); onOk && onOk(); };
    },

    /* ---------- 跳转封装 ---------- */
    go(url) { window.location.href = url; }
};

/* 自动绑定:页面加载后初始化子 Tab */
document.addEventListener('DOMContentLoaded', () => App.bindSubTabs());
