window.UserAdminView = {
    async render() {
        const qs = new URLSearchParams();
        if (state.userKw) qs.set('keyword', state.userKw);
        if (state.userRole) qs.set('role', state.userRole);
        if (state.userStatus) qs.set('status', state.userStatus);
        if (state.userDateFrom) qs.set('beginDate', state.userDateFrom);
        if (state.userDateTo) qs.set('endDate', state.userDateTo);
        const users = await api.get('/api/users' + (qs.toString() ? '?' + qs.toString() : ''));
        state.users = users;   // resetPw / deleteUser 的确认文案要按 id 反查用户名
        const meId = state.currentUser ? (await api.get('/api/auth/me')).userId : null;
        const rows = users.length === 0
            ? `<tr><td colspan="9" class="empty">${t('user.empty')}</td></tr>`
            : users.map(u => {
                const isSelf = meId === u.id;
                return `
                <tr>
                    <td>${u.id}</td>
                    <td>${escapeHtml(u.username)}${isSelf ? ` <span class="badge ok">${t('user.badge.me')}</span>` : ''}</td>
                    <td>${u.role}</td>
                    <td>${u.enabled ? `<span class="badge ok">${t('user.status.enabled')}</span>` : `<span class="badge low">${t('user.status.disabled')}</span>`}</td>
                    <td>${u.mustChangePw ? '⚠️' : ''}</td>
                    <td>${escapeHtml(u.createdBy || '-')}</td>
                    <td>${formatDate(u.createdAt)}</td>
                    <td class="actions">
                        <button class="btn-link" onclick="UserAdminView.viewUser(${u.id})">${t('common.view')}</button>
                        <button class="btn-link" onclick="UserAdminView.editUser(${u.id})">${t('common.edit')}</button>
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
            <div class="card">
                <div class="toolbar">
                    <input id="user-kw" class="input input-sm" placeholder="${t('user.kw_placeholder')}" value="${escapeHtml(state.userKw||'')}" style="width:180px;">
                    <select id="user-role-filter" class="select" style="width:110px;" onchange="UserAdminView.search()">
                        <option value="" ${state.userRole===''?'selected':''}>${t('common.all')} ${t('user.col.role')}</option>
                        <option value="ADMIN" ${state.userRole==='ADMIN'?'selected':''}>${t('user.role.ADMIN')}</option>
                        <option value="OPERATOR" ${state.userRole==='OPERATOR'?'selected':''}>${t('user.role.OPERATOR')}</option>
                    </select>
                    <select id="user-status-filter" class="select" style="width:110px;" onchange="UserAdminView.search()">
                        <option value="" ${state.userStatus===''?'selected':''}>${t('common.all')}</option>
                        <option value="enabled" ${state.userStatus==='enabled'?'selected':''}>${t('user.status.enabled')}</option>
                        <option value="disabled" ${state.userStatus==='disabled'?'selected':''}>${t('user.status.disabled')}</option>
                    </select>
                    <span style="display:inline-flex;align-items:center;gap:4px;"><label style="font-size:13px;color:var(--c-text-3);">${t('orders.begin_label')}</label><input id="user-date-from" type="date" class="input input-sm" value="${state.userDateFrom||''}"></span>
                    <span style="display:inline-flex;align-items:center;gap:4px;"><label style="font-size:13px;color:var(--c-text-3);">${t('orders.end_label')}</label><input id="user-date-to" type="date" class="input input-sm" value="${state.userDateTo||''}"></span>
                    <button class="btn btn-sm" onclick="UserAdminView.search()">${t('common.search')}</button>
                    <button class="btn btn-sm" onclick="UserAdminView.resetSearch()">${t('common.reset')}</button>
                </div>
                <div class="table-wrap">
                    <table class="table">
                        <thead><tr>
                            <th style="width:60px;">ID</th>
                            <th>${t('user.col.username')}</th>
                            <th>${t('user.col.role')}</th>
                            <th style="width:90px;">${t('user.col.enabled')}</th>
                            <th>${t('user.col.must_change')}</th>
                            <th>${t('user.col.createdBy')}</th>
                            <th style="width:160px;">${t('user.col.created_at')}</th>
                            <th style="width:220px;">${t('user.col.actions')}</th>
                        </tr></thead>
                        <tbody>${rows}</tbody>
                    </table>
                </div>
            </div>
        `;
    },
    search() {
        state.userKw = document.getElementById('user-kw')?.value?.trim() || '';
        state.userRole = document.getElementById('user-role-filter')?.value || '';
        state.userStatus = document.getElementById('user-status-filter')?.value || '';
        state.userDateFrom = document.getElementById('user-date-from')?.value || '';
        state.userDateTo = document.getElementById('user-date-to')?.value || '';
        loadUsers();
    },
    resetSearch() {
        state.userKw = '';
        state.userRole = '';
        state.userStatus = '';
        state.userDateFrom = '';
        state.userDateTo = '';
        loadUsers();
    },
    async viewUser(id) {
        try {
            const u = await api.get('/api/users/' + id);
            showModal(t('user.modal.view'), UserAdminView.userDetail(u), null, true);
        } catch (e) { toast(e.message, 'error'); }
    },
    async editUser(id) {
        try {
            const u = await api.get('/api/users/' + id);
            showModal(t('user.modal.edit'), UserAdminView.userForm(u), async () => {
                try {
                    await api.put('/api/users/' + id, {
                        role: document.getElementById('uf-role').value
                    });
                    toast(t('common.success.save'), 'success');
                    loadUsers();
                } catch (e) { toast(e.message, 'error'); return false; }
            });
        } catch (e) { toast(e.message, 'error'); }
    },
    userDetail(u) {
        u = u || {};
        return `
        <div style="display:grid;grid-template-columns:120px 1fr;gap:8px 16px;font-size:14px;">
            <div style="color:var(--c-text-3);">ID</div><div>${u.id}</div>
            <div style="color:var(--c-text-3);">${t('user.col.username')}</div><div><strong>${escapeHtml(u.username)}</strong></div>
            <div style="color:var(--c-text-3);">${t('user.col.role')}</div><div>${u.role}</div>
            <div style="color:var(--c-text-3);">${t('user.col.enabled')}</div><div>${u.enabled ? `<span class="badge ok">${t('user.status.enabled')}</span>` : `<span class="badge low">${t('user.status.disabled')}</span>`}</div>
            <div style="color:var(--c-text-3);">${t('user.col.createdBy')}</div><div>${escapeHtml(u.createdBy || '-')}</div>
            <div style="color:var(--c-text-3);">${t('user.col.created_at')}</div><div>${formatDate(u.createdAt)}</div>
        </div>`;
    },
    userForm(u) {
        u = u || {};
        return `
        <div class="form-row">
            <div class="form-field" style="flex:1;">
                <label>${t('user.col.role')}</label>
                <select id="uf-role" class="select">
                    <option value="OPERATOR" ${u.role==='OPERATOR'?'selected':''}>${t('user.role.OPERATOR')}</option>
                    <option value="ADMIN" ${u.role==='ADMIN'?'selected':''}>${t('user.role.ADMIN')}</option>
                </select>
            </div>
        </div>`;
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
