window.UserAdminView = {
    async render() {
        const users = await api.get('/api/users');
        const meId = state.currentUser ? (await api.get('/api/auth/me')).userId : null;
        const rows = users.map(u => {
            const isSelf = meId === u.id;
            return `
            <tr>
                <td>${u.id}</td>
                <td>${escapeHtml(u.username)}${isSelf ? ' <span class="badge ok">我</span>' : ''}</td>
                <td>${u.role}</td>
                <td>${u.enabled ? '✅' : '❌'}</td>
                <td>${u.mustChangePw ? '⚠️' : ''}</td>
                <td>
                    <button class="btn-link" onclick="UserAdminView.resetPw(${u.id}, '${escapeHtml(u.username)}')">重置密码</button>
                    <button class="btn-link" onclick="UserAdminView.toggleEnabled(${u.id}, ${!u.enabled})">${u.enabled ? '禁用' : '启用'}</button>
                    <button class="btn-link danger" ${isSelf ? 'disabled title="不能删除当前登录的用户"' : ''} onclick="UserAdminView.deleteUser(${u.id}, '${escapeHtml(u.username)}')">删除</button>
                </td>
            </tr>`;
        }).join('');
        return `
            <div class="page-head">
                <div>
                    <h2>用户管理</h2>
                    <div class="desc">维护系统账号与角色。删除用户不会删除其业务记录(库存流水 / 出入库单保留原操作员字段)</div>
                </div>
                <button class="btn btn-primary" onclick="UserAdminView.newUser()">+ 新建用户</button>
            </div>
            <table class="data-table">
                <thead><tr><th>ID</th><th>用户名</th><th>角色</th><th>启用</th><th>待改密</th><th>操作</th></tr></thead>
                <tbody>${rows}</tbody>
            </table>
        `;
    },
    newUser() {
        showModal('新建用户', `
            <label style="display:block;margin-bottom:10px;">用户名 <input id="nu-username" class="input"></label>
            <label style="display:block;margin-bottom:10px;">初始密码 <input id="nu-password" class="input" type="password"></label>
            <label style="display:block;margin-bottom:10px;">角色
                <select id="nu-role" class="select">
                    <option value="OPERATOR">操作员</option>
                    <option value="ADMIN">管理员</option>
                </select>
            </label>
        `, async () => {
            try {
                await api.post('/api/users', {
                    username: document.getElementById('nu-username').value.trim(),
                    password: document.getElementById('nu-password').value,
                    role: document.getElementById('nu-role').value
                });
                toast('创建成功', 'success');
                loadUsers();
            } catch (e) { toast(e.message, 'error'); return false; }
        });
    },
    async resetPw(id, name) {
        const pw = prompt(`为「${name}」设置新密码(至少 6 位):`);
        if (!pw) return;
        try {
            await api.post(`/api/users/${id}/password`, { password: pw });
            toast('已重置', 'success');
        } catch (e) { toast(e.message, 'error'); }
    },
    async toggleEnabled(id, enable) {
        try {
            await api.post(`/api/users/${id}/enabled`, { enabled: enable });
            toast(enable ? '已启用' : '已禁用', 'success');
            loadUsers();
        } catch (e) { toast(e.message, 'error'); }
    },
    async deleteUser(id, name) {
        confirmModal(
            `确认删除用户「${name}」?\n\n该用户的库存流水 / 出入库单等业务记录将保留(operator 字段保留原用户名)。`,
            async () => {
                try {
                    await api.del('/api/users/' + id);
                    toast('已删除', 'success');
                    loadUsers();
                } catch (e) { toast(e.message, 'error'); }
            }
        );
    }
};