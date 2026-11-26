<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { BookOpen, Copy, MessageSquare, Plus, Search, Send, Sparkles, Square, FileText } from 'lucide-vue-next';
import { api } from '../api';
import { suggestions } from '../fixtures';
import { confirmDelete, copy, showError } from '../feedback';
import Markdown from '../Markdown.vue';
import { memoryTypes, strategies, type Conversation, type Strategy, type Turn } from '../model';

const route = useRoute();
const router = useRouter();
const search = ref('');
const draft = ref('');
const strategy = ref<Strategy>('HYBRID_RRF');
const failNext = ref(false);
const library = ref('');
const modal = ref(false);
const editing = ref<Conversation>();
const title = ref('');
const evidenceTurn = ref<Turn>();
const drawer = ref(false);
const scroll = ref<HTMLElement>();
const active = computed(() => api.conversations.find((chat) => chat.id === route.params.id));
const bound = computed(() => api.libraries.find((item) => item.id === active.value?.library));
const pending = computed(() => active.value?.turns.find((turn) => turn.status === 'pending'));
const history = computed(() =>
    api.conversations
        .filter((chat) => chat.title.toLowerCase().includes(search.value.trim().toLowerCase()))
        .sort((a, b) => b.updated - a.updated),
);
watch(
    () => route.params.id,
    () => {
        draft.value = '';
        drawer.value = false;
    },
);
watch(
    () => route.query.library,
    (value) => {
        if (typeof value === 'string' && api.libraries.some((item) => item.id === value)) {
            library.value = value;
            editing.value = undefined;
            modal.value = true;
            void router.replace('/chat');
        }
    },
    { immediate: true },
);
watch(
    () => [active.value?.id, active.value?.turns.length, pending.value?.id],
    async () => {
        await nextTick();
        if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight;
    },
    { flush: 'post' },
);

function open(chat?: Conversation) {
    editing.value = chat;
    title.value = chat?.title || '';
    library.value = bound.value?.id || api.libraries[0]?.id || '';
    modal.value = true;
}
function save() {
    try {
        if (editing.value) api.renameChat(editing.value, title.value);
        else if (library.value) void router.push('/chat/' + api.createChat(library.value));
        modal.value = false;
    } catch (error) {
        showError(error);
    }
}
async function remove(chat: Conversation) {
    if (await confirmDelete(chat.title)) {
        api.deleteChat(chat);
        if (chat === active.value || route.params.id === chat.id) await router.replace('/chat');
    }
}
function send(question = draft.value, selected = strategy.value) {
    if (!question.trim() || !active.value || pending.value || !bound.value) return;
    draft.value = '';
    void api.ask(active.value, question.trim(), selected, failNext.value);
    failNext.value = false;
}
function enter(event: KeyboardEvent) {
    if (event.shiftKey || event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    send();
}
</script>

<template>
    <div class="chat-layout">
        <aside class="history">
            <div class="heading-row">
                <h2>会话</h2>
                <button class="icon" aria-label="新建会话" title="新建会话" @click="open()"><Plus :size="19" /></button>
            </div>
            <el-input v-model="search" placeholder="搜索会话" :prefix-icon="Search" clearable />
            <div class="history-items">
                <div v-for="chat in history" :key="chat.id" class="history-row" :class="{ selected: chat === active }">
                    <RouterLink :to="'/chat/' + chat.id"
                        ><MessageSquare :size="15" /><span>{{ chat.title }}</span></RouterLink
                    >
                    <details class="menu">
                        <summary :aria-label="'会话操作 ' + chat.title">⋯</summary>
                        <div>
                            <button
                                @click="
                                    open(chat);
                                    ($event.target as HTMLElement).closest('details')?.removeAttribute('open');
                                "
                            >
                                重命名</button
                            ><button class="danger" @click="remove(chat)">删除</button>
                        </div>
                    </details>
                </div>
                <p v-if="!history.length" class="muted">没有匹配的会话</p>
            </div>
        </aside>
        <section class="chat-main">
            <header class="chat-header heading-row">
                <div>
                    <h1>AI 问答</h1>
                    <span v-if="active" class="muted">{{ active.title }}</span>
                </div>
                <span v-if="active" class="pill"><BookOpen :size="15" />{{ bound?.name || '知识库已删除' }}</span>
            </header>
            <div ref="scroll" class="chat-scroll">
                <div v-if="!active?.turns.length" class="welcome">
                    <div class="welcome-icon"><Sparkles :size="29" /></div>
                    <h2>{{ active ? '有什么想了解的？' : '从知识库开始提问' }}</h2>
                    <p class="muted">
                        {{
                            active
                                ? '从资料中查找信息，并查看回答引用的原文。'
                                : '新建会话时选择一个知识库，或从左侧继续历史会话。'
                        }}
                    </p>
                    <div v-if="bound" class="prompts">
                        <button v-for="prompt in suggestions" :key="prompt" @click="draft = prompt">
                            {{ prompt }}
                        </button>
                    </div>
                    <el-button v-if="!active" type="primary" :icon="Plus" @click="open()">新建会话</el-button>
                </div>
                <div v-else class="thread">
                    <article v-for="turn in active.turns" :key="turn.id" class="turn">
                        <div class="question">{{ turn.question }}</div>
                        <div class="reply">
                            <div class="reply-icon"><Sparkles :size="16" /></div>
                            <div class="reply-body">
                                <span v-if="turn.status === 'pending'" class="muted" role="status"
                                    >正在检索并生成回答…</span
                                >
                                <template v-else-if="turn.status === 'ready'">
                                    <Markdown :text="turn.answer || ''" />
                                    <div class="toolbar">
                                        <el-button text :icon="Copy" @click="copy(turn.answer || '')"
                                            >复制回答</el-button
                                        >
                                        <el-button
                                            v-if="turn.evidence?.length"
                                            text
                                            :icon="FileText"
                                            @click="
                                                evidenceTurn = turn;
                                                drawer = true;
                                            "
                                            >引用证据（{{ turn.evidence.length }}）</el-button
                                        >
                                    </div>
                                    <details>
                                        <summary>Memory Trace · {{ turn.trace?.length || 0 }} 条</summary>
                                        <ul v-if="turn.trace?.length">
                                            <li v-for="entry in turn.trace" :key="entry.id">
                                                {{ memoryTypes[entry.type] }} · {{ entry.id }}
                                            </li>
                                        </ul>
                                        <p v-else>本轮未注入长期记忆。</p>
                                    </details>
                                    <details>
                                        <summary>技术信息</summary>
                                        <p>{{ turn.strategy }} · Mock · 轮次 {{ turn.id }}</p>
                                    </details>
                                </template>
                                <template v-else>
                                    <p :class="turn.status === 'failed' ? 'error' : 'muted'">
                                        {{ turn.status === 'failed' ? '模拟生成失败，请重新提问。' : '已取消生成。' }}
                                    </p>
                                    <el-button
                                        :disabled="!!pending || !bound"
                                        @click="send(turn.question, turn.strategy)"
                                        >重新提问</el-button
                                    >
                                </template>
                            </div>
                        </div>
                    </article>
                </div>
            </div>
            <div v-if="active && bound" class="composer-wrap">
                <form class="composer" @submit.prevent="send()">
                    <textarea
                        v-model="draft"
                        aria-label="提问"
                        placeholder="输入你的问题…"
                        rows="2"
                        maxlength="4000"
                        @keydown.enter="enter"
                    />
                    <div class="heading-row">
                        <div class="toolbar">
                            <el-select v-model="strategy" aria-label="检索策略" style="width: 174px"
                                ><el-option v-for="mode in strategies" :key="mode" :value="mode" :label="mode"
                            /></el-select>
                            <label class="inline muted"><input v-model="failNext" type="checkbox" />模拟下次失败</label>
                        </div>
                        <el-button v-if="pending" :icon="Square" @click="api.cancel(pending)">取消生成</el-button>
                        <el-button v-else native-type="submit" type="primary" :icon="Send" :disabled="!draft.trim()"
                            >发送</el-button
                        >
                    </div>
                </form>
                <small class="muted">Enter 发送 · Shift + Enter 换行</small>
            </div>
        </section>
        <el-dialog v-model="modal" :title="editing ? '重命名会话' : '新建会话'" width="460px">
            <form class="form" @submit.prevent="save">
                <label v-if="editing">会话名称<el-input v-model="title" maxlength="80" /></label>
                <label v-else
                    >绑定知识库<el-select v-model="library" placeholder="选择知识库"
                        ><el-option
                            v-for="item in api.libraries"
                            :key="item.id"
                            :value="item.id"
                            :label="item.name" /></el-select
                ></label>
                <RouterLink v-if="!editing && !api.libraries.length" to="/libraries">前往创建知识库</RouterLink>
                <div class="dialog-actions">
                    <el-button @click="modal = false">取消</el-button
                    ><el-button native-type="submit" type="primary" :disabled="editing ? !title.trim() : !library">{{
                        editing ? '保存' : '创建'
                    }}</el-button>
                </div>
            </form>
        </el-dialog>
        <el-drawer v-model="drawer" title="引用证据" size="460px">
            <article
                v-for="(item, index) in evidenceTurn?.evidence"
                :key="item.docId + item.index"
                class="surface evidence"
            >
                <strong>{{ index + 1 }}. {{ item.document }}</strong>
                <p class="muted">Chunk {{ item.index + 1 }} · {{ item.heading || '正文' }}</p>
                <pre>{{ item.text }}</pre>
            </article>
        </el-drawer>
    </div>
</template>
