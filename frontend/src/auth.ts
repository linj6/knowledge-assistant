import { ref } from 'vue';
import { ApiError, authPath, request, sessionEvents } from './http';

type Account = { id: string; username: string };
export const sessionError = ref('');
export const account = ref<Account | null>(null);
let session = new AbortController();

function resetSessionChecks() {
    session.abort();
    session = new AbortController();
}

function setAccount(user: Account) {
    if (typeof user?.id !== 'string' || typeof user.username !== 'string') {
        throw new ApiError('账号响应格式不正确', 'protocol');
    }
    if (account.value?.id !== user.id || account.value.username !== user.username) account.value = user;
}

sessionEvents.addEventListener('unauthorized', () => {
    resetSessionChecks();
    account.value = null;
});

export async function refreshAccount() {
    try {
        setAccount(await request<Account>(authPath + '/me', { signal: session.signal }));
        sessionError.value = '';
    } catch (error) {
        if (!(error instanceof ApiError) || (error.status !== 401 && error.kind !== 'aborted')) throw error;
    }
}

export async function authenticate(username: string, password: string, register: boolean) {
    sessionError.value = '';
    const body = { username, password };
    if (register) await request<Account>(authPath + '/register', { method: 'POST', body });
    const user = await request<Account>(authPath + '/login', {
        method: 'POST',
        body,
        notifyUnauthorized: false,
    });
    resetSessionChecks();
    setAccount(user);
}

export async function logout() {
    try {
        await request<null>(authPath + '/logout', { method: 'POST' });
        resetSessionChecks();
        account.value = null;
    } catch (error) {
        if (error instanceof ApiError && error.status === 401) return;
        // 已失效的 Session 也可能先触发 CSRF 拒绝。
        if (error instanceof ApiError && error.status === 403) {
            await refreshAccount();
            if (!account.value) return;
        }
        throw error;
    }
}
