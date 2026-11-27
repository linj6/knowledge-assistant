<script setup lang="ts">
import { ref } from 'vue';
import { useRoute } from 'vue-router';
import {
    BookOpen,
    Brain,
    Layers3,
    LogOut,
    MessageSquare,
    PanelLeftClose,
    PanelLeftOpen,
    Search,
} from 'lucide-vue-next';
import { account, logout } from './auth';
import { errorMessage } from './http';
const route = useRoute();
const busy = ref(false);
const error = ref('');
const compact = ref(false);
const nav = [
    { path: '/chat', title: 'AI 问答', icon: MessageSquare },
    { path: '/libraries', title: '知识库', icon: BookOpen },
    { path: '/retrieval', title: '检索工作台', icon: Search },
    { path: '/memory', title: '长期记忆', icon: Brain },
];
async function signOut() {
    busy.value = true;
    error.value = '';
    try {
        await logout();
    } catch (err) {
        error.value = errorMessage(err);
    } finally {
        busy.value = false;
    }
}
</script>

<template>
    <RouterView v-if="route.meta.guest" />
    <div v-else-if="account" :key="account.id" class="shell" :class="{ compact }">
        <aside class="sidebar">
            <div class="brand">
                <div class="brand-icon"><Layers3 :size="21" /></div>
                <div v-if="!compact"><strong>Knowledge</strong><small>ASSISTANT</small></div>
            </div>
            <button
                class="icon collapse"
                :title="compact ? '展开导航' : '折叠导航'"
                :aria-label="compact ? '展开导航' : '折叠导航'"
                @click="compact = !compact"
            >
                <PanelLeftOpen v-if="compact" :size="18" /><PanelLeftClose v-else :size="18" />
            </button>
            <nav aria-label="主导航">
                <RouterLink
                    v-for="item in nav"
                    :key="item.path"
                    :to="item.path"
                    :title="item.title"
                    :aria-label="item.title"
                    :class="{ selected: route.path.startsWith(item.path) }"
                >
                    <component :is="item.icon" :size="19" /><span v-if="!compact">{{ item.title }}</span>
                </RouterLink>
            </nav>
            <div class="account">
                <div class="avatar" :title="account.username">
                    {{ account.username.slice(0, 1).toUpperCase() }}
                </div>
                <div v-if="!compact" class="account-name">
                    <strong>{{ account.username }}</strong>
                </div>
                <button class="icon" title="退出登录" aria-label="退出登录" :disabled="busy" @click="signOut">
                    <LogOut :size="17" />
                </button>
            </div>
        </aside>
        <main class="workspace">
            <p v-if="error" class="error" role="alert">{{ error }}</p>
            <RouterView />
        </main>
    </div>
</template>
