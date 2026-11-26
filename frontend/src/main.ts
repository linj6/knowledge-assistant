import { createApp, watch } from 'vue';
import { createRouter, createWebHistory } from 'vue-router';
import {
    ElButton,
    ElInput,
    ElInputNumber,
    ElSelect,
    ElOption,
    ElDialog,
    ElDrawer,
    ElSwitch,
    ElPagination,
} from 'element-plus';
import 'element-plus/dist/index.css';
import './style.css';
import App from './App.vue';
import { account, refreshAccount, sessionError } from './auth';
import { errorMessage } from './http';

const router = createRouter({
    history: createWebHistory(),
    routes: [
        { path: '/', redirect: '/chat' },
        { path: '/chat/:id?', component: () => import('./views/Chat.vue') },
        { path: '/libraries', component: () => import('./views/Libraries.vue') },
        { path: '/libraries/:id', component: () => import('./views/Documents.vue') },
        { path: '/libraries/:id/documents/:doc', component: () => import('./views/Document.vue') },
        { path: '/retrieval', component: () => import('./views/Retrieval.vue') },
        { path: '/memory', component: () => import('./views/Memory.vue') },
        { path: '/login', component: () => import('./views/Auth.vue'), meta: { guest: true } },
        { path: '/register', component: () => import('./views/Auth.vue'), meta: { guest: true } },
        { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
});
let sessionChecked = false;
router.beforeEach(async (to) => {
    if (!to.meta.guest || !sessionChecked) {
        try {
            await refreshAccount();
        } catch (error) {
            if (!account.value) {
                sessionError.value = errorMessage(error);
                if (!to.meta.guest) return '/login';
            }
        } finally {
            sessionChecked = true;
        }
    }
    if (!to.meta.guest && !account.value) return '/login';
    if (to.meta.guest && account.value) return '/chat';
});
watch(account, (user) => {
    if (!user && !router.currentRoute.value.meta.guest) void router.replace('/login');
});
const app = createApp(App);
for (const component of [
    ElButton,
    ElInput,
    ElInputNumber,
    ElSelect,
    ElOption,
    ElDialog,
    ElDrawer,
    ElSwitch,
    ElPagination,
]) {
    app.use(component);
}
app.use(router).mount('#app');
