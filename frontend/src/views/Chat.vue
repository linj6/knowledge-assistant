<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { BookOpen, Copy, MessageSquare, Plus, Search, Send, Sparkles, Square, FileText } from 'lucide-vue-next';
import { confirmDelete, copy, showError } from '../feedback';
import { ApiError, errorMessage, request } from '../http';
import { librariesPath, useRead, type Chunk, type Library } from '../management';
import Markdown from '../Markdown.vue';
import { headingLabel, memoryTypes, type MemoryType } from '../model';

type Conversation = { id: string; knowledgeBaseId: string; title: string };
type Status = 'PROCESSING' | 'CANCEL_REQUESTED' | 'ANSWERED' | 'INSUFFICIENT_EVIDENCE' | 'FAILED' | 'CANCELLED';
type Citation = { evidenceId: string; documentId: string; indexVersionId: string; chunkId: string };
type Turn = {
    turnId: string;
    status: Status;
    question: string;
    answer?: string | null;
    citations?: Citation[] | null;
    memoryTrace?: { injectedCount: number; memories: { id: string; type: MemoryType }[] } | null;
    errorCode?: string | null;
};
type Message = Pick<Turn, 'turnId' | 'status' | 'citations' | 'memoryTrace' | 'errorCode'> & {
    role: 'USER' | 'ASSISTANT';
    content: string | null;
};
type Submission = { turnId: string; question: string };

const path = '/api/v1/conversations';
const route = useRoute();
const router = useRouter();
const id = computed(() => (typeof route.params.id === 'string' ? route.params.id : ''));
const chatPath = computed(() => (id.value ? path + '/' + id.value : ''));
const search = ref('');
const draft = ref('');
const library = ref('');
const modal = ref(false);
const editing = ref<Conversation>();
const title = ref('');
const saving = ref(false);
const busy = ref(false);
const cancelling = ref(false);
const evidenceTurn = ref<Turn>();
const selectedCitation = ref<Citation>();
const drawer = ref(false);
const scroll = ref<HTMLElement>();

const { data: libraries } = useRead<Library[]>(computed(() => librariesPath));
const {
    data: history,
    error: historyError,
    reload: reloadHistory,
} = useRead<Conversation[]>(
    computed(() => path + '?' + new URLSearchParams({ keyword: search.value.trim() })),
    250,
);
const { data: active, error: detailError, reload: reloadDetail } = useRead<Conversation>(chatPath);
const bound = computed(() => libraries.value?.find((item) => item.id === active.value?.knowledgeBaseId));
const evidencePath = computed(() => {
    const citation = selectedCitation.value;
    const kb = active.value?.knowledgeBaseId;
    return drawer.value && kb && citation
        ? librariesPath +
              '/' +
              kb +
              '/documents/' +
              citation.documentId +
              '/versions/' +
              citation.indexVersionId +
              '/chunks/' +
              citation.chunkId
        : '';
});
const {
    data: evidence,
    loading: loadingEvidence,
    error: evidenceError,
} = useRead<Omit<Chunk, 'chunkOrder'> & { filename: string }>(evidencePath);

const turns = ref<Turn[]>([]);
const running = (status: Status) => status === 'PROCESSING' || status === 'CANCEL_REQUESTED';
const pending = computed(() => turns.value.find((turn) => running(turn.status)));
const loadingMessages = ref(false);
const messageError = ref('');
const pollError = ref('');
const revision = ref(0);
const pollRevision = ref(0);
const drafts = new Map<string, string>();
const submissions = new Map<string, Submission>();
let view = new AbortController();

watch(
    id,
    (current, previous, cleanup) => {
        if (previous) drafts.set(previous, draft.value);
        draft.value = drafts.get(current) ?? '';
        drawer.value = false;
        turns.value = [];
        busy.value = false;
        cancelling.value = false;
        view = new AbortController();
        const controller = view;
        cleanup(() => controller.abort());
    },
    { immediate: true, flush: 'sync' },
);

function putTurn(turn: Turn) {
    const index = turns.value.findIndex((item) => item.turnId === turn.turnId);
    if (index < 0) turns.value.push(turn);
    else turns.value[index] = turn;
}

watch(
    [chatPath, revision],
    async ([url], _, cleanup) => {
        loadingMessages.value = Boolean(url);
        messageError.value = '';
        if (!url) return;
        const controller = new AbortController();
        const { signal } = controller;
        cleanup(() => controller.abort());
        const records = new Map<string, Turn>();
        try {
            let page = 1;
            let hasNext: boolean;
            do {
                const response = await request<{ items: Message[]; hasNext: boolean }>(
                    url + '/messages?pageSize=100&page=' + page++,
                    { signal },
                );
                for (const item of response.items) {
                    const { turnId, role, content, status, citations, memoryTrace, errorCode } = item;
                    if (role === 'USER') records.set(turnId, { turnId, question: content ?? '', status });
                    else {
                        const turn = records.get(turnId);
                        if (turn) Object.assign(turn, { status, answer: content, citations, memoryTrace, errorCode });
                    }
                }
                hasNext = response.hasNext;
            } while (hasNext);
            if (!signal.aborted) {
                turns.value = [...records.values()];
                const submission = submissions.get(id.value);
                if (submission && records.has(submission.turnId)) {
                    submissions.delete(id.value);
                    if (draft.value.trim() === submission.question) draft.value = '';
                }
            }
        } catch (error) {
            if (!signal.aborted) messageError.value = errorMessage(error);
        } finally {
            if (!signal.aborted) loadingMessages.value = false;
        }
    },
    { immediate: true, flush: 'sync' },
);

const pollPath = computed(() =>
    !loadingMessages.value && chatPath.value && pending.value ? chatPath.value + '/turns/' + pending.value.turnId : '',
);
watch([pollPath, pollRevision], ([url], _, cleanup) => {
    pollError.value = '';
    if (!url) return;
    const controller = new AbortController();
    const { signal } = controller;
    let timer: number;
    cleanup(() => {
        clearTimeout(timer);
        controller.abort();
    });
    async function poll() {
        try {
            const turn = await request<Turn>(url, { signal });
            if (signal.aborted) return;
            pollError.value = '';
            putTurn(turn);
            if (running(turn.status)) timer = window.setTimeout(poll, 1000);
            else {
                reloadDetail();
                reloadHistory();
            }
        } catch (error) {
            if (signal.aborted) return;
            pollError.value = errorMessage(error);
            if (error instanceof ApiError && (error.kind === 'network' || (error.status ?? 0) >= 500)) {
                timer = window.setTimeout(poll, 3000);
            }
        }
    }
    void poll();
});

function refreshChat() {
    reloadDetail();
    revision.value++;
}
watch(
    [() => route.query.library, libraries],
    ([value]) => {
        if (typeof value === 'string' && libraries.value?.some((item) => item.id === value)) {
            open();
            library.value = value;
            void router.replace('/chat');
        }
    },
    { immediate: true },
);
watch(
    () => [id.value, turns.value.length, pending.value?.turnId],
    async () => {
        await nextTick();
        if (scroll.value) scroll.value.scrollTop = scroll.value.scrollHeight;
    },
    { flush: 'post' },
);

function open(chat?: Conversation) {
    editing.value = chat;
    title.value = chat?.title ?? '';
    library.value = bound.value?.id ?? libraries.value?.[0]?.id ?? '';
    modal.value = true;
}
async function save() {
    if (saving.value) return;
    const target = editing.value;
    saving.value = true;
    try {
        const chat = target
            ? await request<Conversation>(path + '/' + target.id, {
                  method: 'PATCH',
                  body: { title: title.value.trim() },
              })
            : await request<Conversation>(path, { method: 'POST', body: { knowledgeBaseId: library.value } });
        modal.value = false;
        reloadHistory();
        if (!target) await router.push('/chat/' + chat.id);
        else if (id.value === chat.id) reloadDetail();
    } catch (error) {
        showError(error);
    } finally {
        saving.value = false;
    }
}
async function remove(chat: Conversation) {
    if (!(await confirmDelete(chat.title))) return;
    try {
        await request(path + '/' + chat.id, { method: 'DELETE' });
        drafts.delete(chat.id);
        submissions.delete(chat.id);
        if (id.value === chat.id) {
            draft.value = '';
            await router.replace('/chat');
        }
        reloadHistory();
    } catch (error) {
        if (error instanceof ApiError && error.code === 40902) {
            showError(new Error('会话正在生成回答，取消或等待本轮结束后再删除。'));
        } else showError(error);
    }
}
async function send(question = draft.value) {
    const text = question.trim();
    if (
        !text ||
        text.length > 4000 ||
        !bound.value ||
        pending.value ||
        busy.value ||
        loadingMessages.value ||
        messageError.value
    )
        return;
    const current = id.value;
    const url = chatPath.value;
    const { signal } = view;
    const previous = submissions.get(current);
    if (previous && previous.question !== text) {
        showError(new Error('上次提交结果未知，请先用原问题重试。'));
        return;
    }
    const submission = previous ?? { turnId: crypto.randomUUID(), question: text };
    submissions.set(current, submission);
    busy.value = true;
    try {
        const accepted = await request<Pick<Turn, 'turnId' | 'status'>>(url + '/turns', {
            method: 'POST',
            body: submission,
            signal,
        });
        if (signal.aborted) return;
        submissions.delete(current);
        if (draft.value.trim() === text) draft.value = '';
        putTurn({ ...submission, ...accepted });
        reloadDetail();
        reloadHistory();
        if (!running(accepted.status)) revision.value++;
    } catch (error) {
        if (signal.aborted) return;
        if (
            error instanceof ApiError &&
            error.kind !== 'network' &&
            error.kind !== 'protocol' &&
            (error.status ?? 0) < 500
        ) {
            submissions.delete(current);
        }
        if (error instanceof ApiError && error.code === 40902) {
            draft.value ||= text;
            revision.value++;
        }
        if (submissions.has(current)) {
            draft.value = text;
            showError(new Error('提交结果未知，请再次发送同一问题。' + errorMessage(error)));
        } else showError(error);
    } finally {
        if (!signal.aborted) busy.value = false;
    }
}
async function cancel() {
    const turn = pending.value;
    if (!turn || cancelling.value) return;
    const { signal } = view;
    cancelling.value = true;
    try {
        await request(chatPath.value + '/turns/' + turn.turnId + '/cancel', { method: 'POST', signal });
        if (!signal.aborted) pollRevision.value++;
    } catch (error) {
        if (!signal.aborted) showError(error);
    } finally {
        if (!signal.aborted) cancelling.value = false;
    }
}
function openEvidence(turn: Turn, citation = turn.citations?.[0]) {
    evidenceTurn.value = turn;
    selectedCitation.value = citation;
    drawer.value = true;
}
function enter(event: KeyboardEvent) {
    if (event.shiftKey || event.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    void send();
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
                <p v-if="historyError" class="error" role="alert">{{ historyError }}</p>
                <div
                    v-for="chat in history"
                    :key="chat.id"
                    class="history-row"
                    :class="{ selected: chat.id === active?.id }"
                >
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
                <p v-if="!history?.length" class="muted">没有匹配的会话</p>
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
                <p v-if="loadingMessages" role="status">正在加载消息…</p>
                <p v-if="messageError || detailError || pollError" class="error" role="alert">
                    {{ messageError || detailError || pollError }}
                    <el-button text @click="refreshChat">重试</el-button>
                </p>
                <div v-if="!turns.length" class="welcome">
                    <div class="welcome-icon"><Sparkles :size="29" /></div>
                    <h2>{{ active ? '有什么想了解的？' : '从知识库开始提问' }}</h2>
                    <p class="muted">
                        {{
                            active
                                ? '从资料中查找信息，并查看回答引用的原文。'
                                : '新建会话时选择一个知识库，或从左侧继续历史会话。'
                        }}
                    </p>
                    <el-button v-if="!active" type="primary" :icon="Plus" @click="open()">新建会话</el-button>
                </div>
                <div v-else class="thread">
                    <article v-for="turn in turns" :key="turn.turnId" class="turn">
                        <div class="question">{{ turn.question }}</div>
                        <div class="reply">
                            <div class="reply-icon"><Sparkles :size="16" /></div>
                            <div class="reply-body">
                                <span v-if="running(turn.status)" class="muted" role="status">{{
                                    turn.status === 'CANCEL_REQUESTED' ? '正在取消…' : '正在检索并生成回答…'
                                }}</span>
                                <template
                                    v-else-if="turn.status === 'ANSWERED' || turn.status === 'INSUFFICIENT_EVIDENCE'"
                                >
                                    <Markdown
                                        :text="turn.answer || '没有足够的证据回答此问题。'"
                                        :citation-ids="turn.citations?.map((citation) => citation.evidenceId)"
                                        @citation="
                                            openEvidence(
                                                turn,
                                                turn.citations?.find((item) => item.evidenceId === $event),
                                            )
                                        "
                                    />
                                    <div v-if="turn.citations?.length" class="toolbar">
                                        <el-button
                                            v-for="citation in turn.citations"
                                            :key="citation.evidenceId"
                                            text
                                            @click="openEvidence(turn, citation)"
                                            >[{{ citation.evidenceId }}]</el-button
                                        >
                                    </div>
                                    <div class="toolbar">
                                        <el-button text :icon="Copy" @click="copy(turn.answer || '')"
                                            >复制回答</el-button
                                        >
                                        <el-button
                                            v-if="turn.citations?.length"
                                            text
                                            :icon="FileText"
                                            @click="openEvidence(turn)"
                                            >引用证据（{{ turn.citations.length }}）</el-button
                                        >
                                    </div>
                                    <details>
                                        <summary>Memory Trace · {{ turn.memoryTrace?.injectedCount || 0 }} 条</summary>
                                        <ul v-if="turn.memoryTrace?.injectedCount">
                                            <li v-for="entry in turn.memoryTrace?.memories" :key="entry.id">
                                                {{ memoryTypes[entry.type] }} · {{ entry.id }}
                                            </li>
                                        </ul>
                                        <p v-else>本轮未注入长期记忆。</p>
                                    </details>
                                    <details>
                                        <summary>技术信息</summary>
                                        <p>{{ turn.status }} · 轮次 {{ turn.turnId }}</p>
                                    </details>
                                </template>
                                <template v-else>
                                    <p :class="turn.status === 'FAILED' ? 'error' : 'muted'">
                                        {{
                                            turn.status === 'FAILED'
                                                ? '生成失败：' + (turn.errorCode || '未知错误')
                                                : '已取消生成。'
                                        }}
                                    </p>
                                    <el-button
                                        :disabled="!!pending || busy || loadingMessages"
                                        @click="send(turn.question)"
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
                    <div class="heading-row" style="justify-content: flex-end">
                        <el-button
                            v-if="pending"
                            :disabled="cancelling || pending.status === 'CANCEL_REQUESTED'"
                            :icon="Square"
                            @click="cancel()"
                            >取消生成</el-button
                        >
                        <el-button
                            v-else
                            native-type="submit"
                            type="primary"
                            :icon="Send"
                            :disabled="!draft.trim() || busy || loadingMessages || !!messageError"
                            >发送</el-button
                        >
                    </div>
                </form>
                <small class="muted">Enter 发送 · Shift + Enter 换行</small>
            </div>
        </section>
        <el-dialog v-model="modal" :title="editing ? '重命名会话' : '新建会话'" width="460px">
            <form class="form" @submit.prevent="save">
                <label v-if="editing">会话名称<el-input v-model="title" maxlength="400" /></label>
                <label v-else
                    >绑定知识库<el-select v-model="library" placeholder="选择知识库"
                        ><el-option
                            v-for="item in libraries"
                            :key="item.id"
                            :value="item.id"
                            :label="item.name" /></el-select
                ></label>
                <RouterLink v-if="!editing && !libraries?.length" to="/libraries">前往创建知识库</RouterLink>
                <div class="dialog-actions">
                    <el-button @click="modal = false">取消</el-button
                    ><el-button
                        native-type="submit"
                        type="primary"
                        :loading="saving"
                        :disabled="editing ? !title.trim() || [...title.trim()].length > 200 : !library"
                        >{{ editing ? '保存' : '创建' }}</el-button
                    >
                </div>
            </form>
        </el-dialog>
        <el-drawer v-model="drawer" title="引用证据" size="460px">
            <div class="toolbar">
                <el-button
                    v-for="citation in evidenceTurn?.citations"
                    :key="citation.evidenceId"
                    :type="citation === selectedCitation ? 'primary' : 'default'"
                    @click="selectedCitation = citation"
                    >{{ citation.evidenceId }}</el-button
                >
            </div>
            <p v-if="loadingEvidence" role="status">正在加载证据…</p>
            <p v-else-if="evidenceError" class="error" role="alert">该引用证据当前无法查看。{{ evidenceError }}</p>
            <article v-else-if="evidence" class="surface evidence">
                <strong>{{ evidence.filename }}</strong>
                <p class="muted">{{ headingLabel(evidence.headingPath) }}</p>
                <p class="muted">
                    Chunk {{ evidence.chunkId }} · 来源区间 [{{ evidence.sourceStart }},
                    {{ evidence.sourceEnd }})（Unicode 码点）
                </p>
                <pre>{{ evidence.content }}</pre>
            </article>
        </el-drawer>
    </div>
</template>
