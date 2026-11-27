<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Layers3 } from 'lucide-vue-next';
import { authenticate, sessionError } from '../auth';
import { errorMessage } from '../http';

const route = useRoute();
const router = useRouter();
const register = computed(() => route.path === '/register');
const user = ref('');
const password = ref('');
const confirmation = ref('');
const busy = ref(false);
const error = ref('');
async function submit() {
    if (busy.value) return;
    error.value = '';
    if (register.value && password.value !== confirmation.value) {
        error.value = '两次输入的密码不一致。';
        return;
    }
    busy.value = true;
    try {
        await authenticate(user.value, password.value, register.value);
        await router.replace('/chat');
    } catch (err) {
        error.value = errorMessage(err);
    } finally {
        busy.value = false;
    }
}
</script>

<template>
    <div class="auth">
        <form class="surface auth-card" @submit.prevent="submit">
            <div class="brand-icon"><Layers3 :size="24" /></div>
            <h1>{{ register ? '创建账号' : '登录 Knowledge Assistant' }}</h1>
            <p class="muted">使用服务端账号登录。</p>
            <label
                >账号<el-input
                    v-model="user"
                    placeholder="3–64 位字母、数字或下划线"
                    autocomplete="username"
                    maxlength="64"
            /></label>
            <label
                >密码<el-input
                    v-model="password"
                    type="password"
                    placeholder="至少 8 位密码（最多 72 字节）"
                    show-password
                    :autocomplete="register ? 'new-password' : 'current-password'"
            /></label>
            <label v-if="register"
                >确认密码<el-input v-model="confirmation" type="password" autocomplete="new-password"
            /></label>
            <p v-if="error || sessionError" class="error" role="alert">{{ error || sessionError }}</p>
            <el-button type="primary" native-type="submit" :loading="busy">{{
                register ? '注册并进入' : '登录'
            }}</el-button>
            <RouterLink
                :to="register ? '/login' : '/register'"
                @click="
                    error = '';
                    password = '';
                    confirmation = '';
                "
                >{{ register ? '已有账号，返回登录' : '注册账号' }}</RouterLink
            >
        </form>
    </div>
</template>
