<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Layers3 } from 'lucide-vue-next';
import { api } from '../api';

const route = useRoute();
const router = useRouter();
const register = computed(() => route.path === '/register');
const user = ref('');
const password = ref('');
const confirmation = ref('');
const busy = ref(false);
const error = ref('');
async function submit() {
    error.value = '';
    if (register.value && password.value !== confirmation.value) {
        error.value = '两次输入的密码不一致。';
        return;
    }
    busy.value = true;
    try {
        await api.login(user.value, password.value);
        await router.replace('/chat');
    } catch (err) {
        error.value = (err as Error).message;
    } finally {
        busy.value = false;
    }
}
</script>

<template>
    <div class="auth">
        <form class="surface auth-card" @submit.prevent="submit">
            <div class="brand-icon"><Layers3 :size="24" /></div>
            <h1>{{ register ? '创建演示账号' : '登录 Knowledge Assistant' }}</h1>
            <p class="muted">使用本地演示账号体验知识库与 AI 问答。</p>
            <label
                >账号<el-input v-model="user" placeholder="输入演示账号" autocomplete="username" maxlength="40"
            /></label>
            <label
                >密码<el-input
                    v-model="password"
                    type="password"
                    placeholder="至少 6 位演示密码"
                    show-password
                    :autocomplete="register ? 'new-password' : 'current-password'"
            /></label>
            <label v-if="register"
                >确认密码<el-input v-model="confirmation" type="password" autocomplete="new-password"
            /></label>
            <p v-if="error" class="error" role="alert">{{ error }}</p>
            <el-button type="primary" native-type="submit" :loading="busy">{{
                register ? '注册并进入' : '登录'
            }}</el-button>
            <el-button
                v-if="!register"
                :disabled="busy"
                @click="
                    user = 'demo';
                    password = 'demo123';
                    submit();
                "
                >使用演示账号</el-button
            >
            <RouterLink
                :to="register ? '/login' : '/register'"
                @click="
                    error = '';
                    password = '';
                    confirmation = '';
                "
                >{{ register ? '已有账号，返回登录' : '注册演示账号' }}</RouterLink
            >
        </form>
    </div>
</template>
