import { ApiError, request, resetSessionRequests, setUnauthorizedHandler } from './api.js';
import { AppState, updateState } from './state.js';

const authPath = '/api/v1/auth/';
const expiryNotice = 'knowledge-assistant.session-expired';

export function initializeAuth(onAuthenticated, onSessionEnded) {
    const byId = (id) => document.getElementById(id);
    const panel = byId('auth');
    const title = byId('auth-title');
    const message = byId('auth-status');
    const form = byId('auth-form');
    const retry = byId('auth-retry');
    const username = byId('auth-username');
    const password = byId('auth-password');
    const submit = byId('auth-submit');
    const toggle = byId('auth-toggle');
    const workspace = byId('workspace');
    const nav = byId('workspace-nav');
    const currentUser = byId('current-user');
    const logout = byId('logout');
    const logoutStatus = byId('logout-status');
    let registering = false;

    function showForm(register, notice = '') {
        registering = register;
        title.textContent = register ? '注册账号' : '登录';
        submit.textContent = register ? '注册' : '登录';
        toggle.textContent = register ? '返回登录' : '注册账号';
        password.autocomplete = register ? 'new-password' : 'current-password';
        password.value = '';
        message.textContent = notice;
        form.hidden = false;
        username.focus();
    }

    function enterWorkspace(user) {
        if (typeof user?.id !== 'string' || !user.id || typeof user.username !== 'string' || !user.username) {
            throw new ApiError('protocol');
        }
        updateState({ user });
        password.value = '';
        username.value = '';
        panel.hidden = true;
        workspace.hidden = nav.hidden = false;
        currentUser.textContent = user.username;
        currentUser.focus();
        void onAuthenticated();
    }

    function endSession(expired) {
        if (!AppState.user) return;
        // 先隔离旧请求和界面，再重载以释放各业务模块的本地状态。
        resetSessionRequests();
        workspace.hidden = nav.hidden = true;
        onSessionEnded();
        updateState({ user: null, knowledgeBases: [], selectedKnowledgeBaseId: '', initialized: false });
        workspace.replaceChildren();
        nav.replaceChildren();
        try {
            if (expired) sessionStorage.setItem(expiryNotice, '1');
        } finally {
            location.reload();
        }
    }

    setUnauthorizedHandler(() => endSession(true));

    async function restoreSession() {
        if (retry.disabled) return;
        retry.disabled = true;
        retry.hidden = true;
        title.textContent = '正在确认登录状态';
        message.textContent = '';
        try {
            const { data } = await request(authPath + 'me');
            enterWorkspace(data);
            sessionStorage.removeItem(expiryNotice);
        } catch (error) {
            if (error.status === 401) {
                const expired = sessionStorage.getItem(expiryNotice);
                sessionStorage.removeItem(expiryNotice);
                showForm(false, expired ? '登录已过期，请重新登录。' : '');
            } else {
                title.textContent = '连接异常';
                message.textContent = '暂时无法确认登录状态，请重试。';
                retry.hidden = false;
                retry.disabled = false;
                retry.focus();
            }
        } finally {
            retry.disabled = false;
        }
    }

    retry.addEventListener('click', restoreSession);
    toggle.addEventListener('click', () => showForm(!registering));
    form.addEventListener('submit', async (event) => {
        event.preventDefault();
        if (form.hidden || submit.disabled || AppState.user) return;
        const body = { username: username.value, password: password.value };
        submit.disabled = toggle.disabled = username.disabled = password.disabled = true;
        message.textContent = registering ? '正在注册…' : '正在登录…';
        try {
            const { data } = await request(authPath + (registering ? 'register' : 'login'), { method: 'POST', body });
            if (registering) {
                showForm(false, '注册成功，请登录。');
            } else {
                resetSessionRequests();
                enterWorkspace(data);
            }
        } catch (error) {
            password.value = '';
            if (error.status === 401 && !registering) message.textContent = '用户名或密码错误。';
            else if (error.status === 409 && registering) message.textContent = '用户名已存在，请使用其他用户名。';
            else if (error.status === 400)
                message.textContent =
                    '用户名须为 3–64 位字母、数字或下划线。密码须至少 8 个字符、最多 72 字节，且不能包含控制字符。';
            else if (error.status === 403) message.textContent = error.message;
            else message.textContent = '暂时无法完成操作，请检查连接后重试。';
        } finally {
            submit.disabled = toggle.disabled = username.disabled = password.disabled = false;
            if (!panel.hidden) username.focus();
        }
    });

    logout.addEventListener('click', async () => {
        if (logout.disabled || !AppState.user) return;
        logout.disabled = true;
        logoutStatus.textContent = '正在退出…';
        try {
            await request(authPath + 'logout', { method: 'POST' });
            endSession(false);
        } catch (error) {
            if (AppState.user) {
                logoutStatus.textContent = error.status === 403 ? error.message : '退出状态未确认，请重试。';
                logout.disabled = false;
            }
        }
    });

    return restoreSession();
}
