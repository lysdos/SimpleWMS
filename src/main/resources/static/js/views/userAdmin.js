window.UserAdminView = {
    async render() {
        const users = await api.get('/api/users');
        const meId = state.currentUser ? (await api.get('/api/auth/me')).userId : null;
        const rows = users.map(u => {
            const isSelf = meId === u.id;
            return `
            <tr>
                <td>${u.id}</td>
                <td>${escapeHtml(u.username)}${isSelf ? ` <span class="badge ok">${t('user.badge.me')}</span>` : ''}</td>
                <td>${u.role}</td>
                <td>${u.enabled ? '✅' : '❌'}</td>
                <td>${u.mustChangePw ? '⚠️' : ''}</td>
                <td>
                    <button class="btn-link" onclick="UserAdminView.resetPw(${u.id})">${t('user.btn.reset_pw')}</button>
                    <button class="btn-link" onclick="UserAdminView.toggleEnabled(${u.id}, ${!u.enabled})">${u.enabled ? t('user.btn.disable') : t('user.btn.enable')}</button>
                    <button class="btn-link danger" ${isSelf ? `disabled title="${t('user.cant_delete_self')}"` : ''} onclick="UserAdminView.deleteUser(${u.id})">${t('user.btn.delete')}</button>
                </td>
            </tr>`;
        }).join('');
        return `
            <div class="page-head">
                <div>
                    <h2>${t('user.title')}</h2>
                    <div class="desc">${t('user.desc')}</div>
                </div>
                <button class="btn btn-primary" onclick="UserAdminView.newUser()">${t('user.create')}</button>
            </div>
            <table class="data-table">
                <thead><tr>
                    <th>ID</th>
                    <th>${t('user.col.username')}</th>
                    <th>${t('user.col.role')}</th>
                    <th>${t('user.col.enabled')}</th>
                    <th>${t('user.col.must_change')}</th>
                    <th>${t('user.col.actions')}</th>
                </tr></thead>
                <tbody>${rows}</tbody>
            </table>
        `;
    },
    newUser() {
        showModal(t('user.modal.new'), `
            <label style="display:block;margin-bottom:10px;">${t('user.form.username')} <input id="nu-username" class="input"></label>
            <label style="display:block;margin-bottom:10px;">${t('user.form.password')} <input id="nu-password" class="input" type="password"></label>
            <label style="display:block;margin-bottom:10px;">${t('user.form.role')}
                <select id="nu-role" class="select">
                    <option value="OPERATOR">${t('user.role.OPERATOR')}</option>
                    <option value="ADMIN">${t('user.role.ADMIN')}</option>
                </select>
            </label>
        `, async () => {
            try {
                await api.post('/api/users', {
                    username: document.getElementById('nu-username').value.trim(),
                    password: document.getElementById('nu-password').value,
                    role: document.getElementById('nu-role').value
                });
                toast(t('user.toast.created'), 'success');
                loadUsers();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    },
    async resetPw(id) {
        // 不再接受 name 参数(避免用户名拼进 inline onclick)。从已加载的 state.users
        // 查显示名;若未加载回退用 ID。
        const u = (state.users || []).find(x => x.id === id);
        const label = u ? u.username : ('#' + id);
        const pw = prompt(t('user.prompt.reset_pw', { n: label }));
        if (!pw) return;
        try {
            await api.post(`/api/users/${id}/password`, { password: pw });
            toast(t('user.toast.reset_done'), 'success');
        } catch (e) { toast(e.message, 'error'); }
    },
    async toggleEnabled(id, enable) {
        try {
            await api.post(`/api/users/${id}/enabled`, { enabled: enable });
            toast(enable ? t('user.toast.enabled') : t('user.toast.disabled'), 'success');
            loadUsers();
        } catch (e) { toast(e.message, 'error'); }
    },
    async deleteUser(id) {
        const u = (state.users || []).find(x => x.id === id);
        const label = u ? u.username : ('#' + id);
        confirmModal(t('user.confirm.del', { n: label }), async () => {
            try {
                await api.del('/api/users/' + id);
                toast(t('user.toast.deleted'), 'success');
                loadUsers();
            } catch (e) { toast(e.message, 'error'); }
        });
    }
};
