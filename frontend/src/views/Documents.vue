<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { FileText, MessageSquare, Search, UploadCloud } from 'lucide-vue-next';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ApiError, errorMessage, request } from '../http';
import { confirmDelete } from '../feedback';
import { dateLabel } from '../model';
import {
    librariesPath,
    taskPath,
    useRead,
    useTaskPolling,
    splitLabels,
    statusLabels,
    documentStatus,
    statusClass,
    failureLabel,
    type Library,
    type DocumentRecord,
    type Strategy,
    type Task,
    type Chunking,
    type Page,
} from '../management';

const route = useRoute();
const router = useRouter();
const libraryPath = computed(() => librariesPath + '/' + route.params.id);
const {
    data: library,
    loading: libraryLoading,
    error: libraryError,
    reload: reloadLibrary,
} = useRead<Library>(libraryPath);
const query = ref('');
const status = ref('');
const page = ref(1);
const pageSize = 5;
const filters = { IMPORTING: '导入中', FAILED: '失败', PUBLISHED: '已发布', UNPUBLISHED: '未发布' };
const listQuery = computed(() => ({
    keyword: query.value || undefined,
    status: status.value || undefined,
    page: page.value > 1 ? String(page.value) : undefined,
}));
watch(
    [query, status],
    () => {
        page.value = 1;
    },
    { flush: 'sync' },
);
const docsPath = computed(() => (library.value ? libraryPath.value + '/documents' : ''));
const listPath = computed(() => {
    if (!docsPath.value) return '';
    const params = new URLSearchParams({ page: String(page.value), pageSize: String(pageSize) });
    if (query.value.trim()) params.set('keyword', query.value.trim());
    if (status.value) params.set('status', status.value);
    return docsPath.value + '?' + params;
});
const { data: records, loading, error, reload } = useRead<Page<DocumentRecord>>(listPath, 180);
const pageRecords = computed(() => records.value?.items ?? []);
watch(records, (result) => {
    if (result) page.value = Math.min(page.value, Math.max(1, Math.ceil(Number(result.total) / pageSize)));
});
const tasks = computed(() => pageRecords.value.flatMap((doc) => (doc.importTask ? [doc.importTask] : [])));
const { pollError, resumePolling } = useTaskPolling(tasks, reload);

const dialog = ref(false);
const file = ref<File>();
const strategy = ref<Strategy>();
const size = ref<number>();
const overlap = ref<number>();
const busy = ref(false);
const changing = ref(false);
const dragging = ref(false);
const validSplit = computed(() => {
    const length = size.value ?? 800;
    const shared = overlap.value ?? 120;
    return (
        Number.isInteger(length) &&
        length >= 1 &&
        length <= 800 &&
        Number.isInteger(shared) &&
        shared >= 0 &&
        shared < length
    );
});
watch(
    [libraryPath, () => route.query],
    ([, params]) => {
        dialog.value = false;
        query.value = typeof params.keyword === 'string' ? params.keyword : '';
        status.value = typeof params.status === 'string' && Object.hasOwn(filters, params.status) ? params.status : '';
        const requestedPage = typeof params.page === 'string' ? Number(params.page) : 1;
        page.value =
            Number.isInteger(requestedPage) && requestedPage > 0 && requestedPage <= 2147483647 ? requestedPage : 1;
    },
    { immediate: true },
);
function open() {
    file.value = undefined;
    strategy.value = undefined;
    size.value = undefined;
    overlap.value = undefined;
    dialog.value = true;
}
function choose(files?: FileList | null) {
    if (!files?.length) return;
    file.value = undefined;
    if (files.length !== 1) {
        ElMessage.error('每次请选择一个文件');
        return;
    }
    const candidate = files[0];
    if (!/\.(md|markdown|txt)$/i.test(candidate.name) || candidate.size > 2097152 || !candidate.size) {
        ElMessage.error('请选择不超过 2 MiB 的非空 Markdown 或 TXT 文件');
        return;
    }
    file.value = candidate;
}
async function submit() {
    if (!file.value || !docsPath.value || busy.value || !validSplit.value) return;
    const path = docsPath.value;
    const body = new FormData();
    body.append('file', file.value);
    if (strategy.value) body.append('strategy', strategy.value);
    if (size.value != null) body.append('chunkSize', String(size.value));
    if (overlap.value != null) body.append('chunkOverlap', String(overlap.value));
    busy.value = true;
    try {
        let statusCode = 0;
        const task = await request<Task & { chunkingConfig: Chunking }>(path + ':import', {
            method: 'POST',
            body,
            onSuccessStatus: (value) => {
                statusCode = value;
            },
        });
        if (path !== docsPath.value) return;
        if (statusCode !== 200 && statusCode !== 202) throw new ApiError('上传响应状态不正确', 'protocol', statusCode);
        query.value = '';
        status.value = '';
        page.value = 1;
        dialog.value = false;
        reload();
        if (statusCode === 200) {
            const config = task.chunkingConfig;
            await ElMessageBox.confirm(
                `已有相同内容的文档，沿用${splitLabels[config.strategy]}，目标长度 ${config.chunkSize}，重叠 ${config.chunkOverlap}。`,
                '重复上传',
                { confirmButtonText: '查看已有文档', cancelButtonText: '关闭' },
            ).then(
                () => {
                    if (path === docsPath.value)
                        void router.push('/libraries/' + route.params.id + '/documents/' + task.documentId);
                },
                () => {},
            );
        } else {
            ElMessage.success('已提交导入任务');
        }
    } catch (failure) {
        if (path === docsPath.value) ElMessage.error(errorMessage(failure));
    } finally {
        busy.value = false;
    }
}
async function change(doc: DocumentRecord, retry = false) {
    if (changing.value) return;
    const path = docsPath.value;
    changing.value = true;
    try {
        if (retry && doc.importTask) {
            await request<Task>(taskPath(doc.importTask.importTaskId) + ':retry', { method: 'POST' });
        } else {
            if (!(await confirmDelete(doc.filename)) || path !== docsPath.value) return;
            await request(path + '/' + doc.documentId, { method: 'DELETE' });
        }
        if (path !== docsPath.value) return;
        ElMessage.success(retry ? '已提交重试' : '文档已删除');
        reload();
    } catch (failure) {
        if (path === docsPath.value) {
            ElMessage.error(errorMessage(failure));
            if (failure instanceof ApiError && failure.status === 409) reload();
        }
    } finally {
        changing.value = false;
    }
}
</script>
<template>
    <section class="page">
        <RouterLink class="breadcrumb" to="/libraries">知识库</RouterLink>
        <template v-if="library">
            <header class="page-heading heading-row">
                <div>
                    <h1>{{ library.name }}</h1>
                    <p>{{ library.description || '暂无描述' }}</p>
                    <small class="muted">更新于 {{ dateLabel(Date.parse(library.updatedAt)) }}</small>
                </div>
                <div class="toolbar">
                    <RouterLink
                        class="action-link"
                        :to="{ path: '/retrieval', query: { ...listQuery, library: library.id } }"
                        ><Search :size="16" />检索测试</RouterLink
                    >
                    <RouterLink class="action-link" :to="{ path: '/chat', query: { library: library.id } }"
                        ><MessageSquare :size="16" />开始问答</RouterLink
                    >
                    <el-button type="primary" :icon="UploadCloud" @click="open">上传文档</el-button>
                </div>
            </header>
            <div class="surface table-panel">
                <div class="heading-row table-heading">
                    <h2>文档列表</h2>
                    <div class="toolbar">
                        <el-input
                            v-model="query"
                            class="search"
                            placeholder="搜索文档名称"
                            :prefix-icon="Search"
                            clearable
                        />
                        <el-select v-model="status" aria-label="导入状态筛选" style="width: 145px"
                            ><el-option label="全部状态" value="" /><el-option
                                v-for="(label, key) in filters"
                                :key="key"
                                :value="key"
                                :label="label"
                        /></el-select>
                    </div>
                </div>
                <table class="data-table">
                    <thead>
                        <tr>
                            <th>文档名</th>
                            <th>状态</th>
                            <th>切分策略</th>
                            <th>Chunk</th>
                            <th>更新时间</th>
                            <th>操作</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr v-for="doc in pageRecords" :key="doc.documentId">
                            <td>
                                <RouterLink
                                    :to="{
                                        path: '/libraries/' + library.id + '/documents/' + doc.documentId,
                                        query: listQuery,
                                    }"
                                    ><FileText :size="15" />{{ doc.filename }}</RouterLink
                                >
                            </td>
                            <td>
                                <span class="status" :class="statusClass(doc)">{{
                                    statusLabels[documentStatus(doc)]
                                }}</span>
                                <div v-if="failureLabel(doc.importTask)" class="error">
                                    {{ failureLabel(doc.importTask) }}
                                </div>
                            </td>
                            <td>{{ doc.chunkingConfig ? splitLabels[doc.chunkingConfig.strategy] : '—' }}</td>
                            <td>{{ doc.chunkCount }}</td>
                            <td>
                                {{
                                    dateLabel(Date.parse(doc.importTask?.updatedAt ?? doc.publishedAt ?? doc.createdAt))
                                }}
                            </td>
                            <td>
                                <div class="toolbar">
                                    <button
                                        v-if="documentStatus(doc) === 'FAILED'"
                                        class="text-btn"
                                        :disabled="changing"
                                        @click="change(doc, true)"
                                    >
                                        重试</button
                                    ><button class="text-btn danger" :disabled="changing" @click="change(doc)">
                                        删除
                                    </button>
                                </div>
                            </td>
                        </tr>
                    </tbody>
                </table>
                <div v-if="loading" class="empty">正在加载文档…</div>
                <div v-else-if="error || pollError" class="empty">
                    {{ error || pollError }} <el-button @click="resumePolling">重新加载</el-button>
                </div>
                <div v-else-if="!Number(records?.total ?? 0)" class="empty">没有匹配的文档</div>
                <el-pagination
                    v-else
                    v-model:current-page="page"
                    :page-size="pageSize"
                    :total="Number(records?.total ?? 0)"
                    layout="prev, pager, next, total"
                />
            </div>
            <el-dialog
                v-model="dialog"
                title="上传文档"
                width="540px"
                :close-on-click-modal="!busy"
                :close-on-press-escape="!busy"
                :show-close="!busy"
            >
                <form class="form" @submit.prevent="submit">
                    <label
                        class="drop-zone"
                        :class="{ dragging }"
                        @dragover.prevent="dragging = true"
                        @dragleave="dragging = false"
                        @drop.prevent="
                            dragging = false;
                            !busy && choose($event.dataTransfer?.files);
                        "
                    >
                        <UploadCloud :size="26" /><strong>{{ file?.name || '点击选择文件或拖拽到这里' }}</strong>
                        <span>UTF-8 Markdown / TXT · 最大 2 MiB</span>
                        <input
                            type="file"
                            aria-label="选择文档"
                            accept=".md,.markdown,.txt"
                            :disabled="busy"
                            @change="
                                choose(($event.target as HTMLInputElement).files);
                                ($event.target as HTMLInputElement).value = '';
                            "
                        />
                    </label>
                    <label
                        >切分策略<el-select
                            v-model="strategy"
                            placeholder="后端默认（固定长度）"
                            clearable
                            :disabled="busy"
                            ><el-option
                                v-for="(label, key) in splitLabels"
                                :key="key"
                                :value="key"
                                :label="label" /></el-select
                    ></label>
                    <p class="muted">
                        {{
                            strategy !== 'MARKDOWN_STRUCTURE_AWARE'
                                ? '按目标长度切分，重叠部分保留相邻片段的上下文。'
                                : '优先沿 Markdown 标题结构切分，超长段落继续按目标长度拆分。'
                        }}
                    </p>
                    <div class="form-columns">
                        <label
                            >Chunk 目标长度<el-input-number
                                v-model="size"
                                placeholder="默认 800"
                                :min="1"
                                :max="800"
                                :precision="0"
                                :disabled="busy"
                        /></label>
                        <label
                            >重叠长度<el-input-number
                                v-model="overlap"
                                placeholder="默认 120"
                                :min="0"
                                :max="799"
                                :precision="0"
                                :disabled="busy"
                        /></label>
                    </div>
                    <p v-if="!validSplit" class="error">目标长度须为 1–800，重叠长度须小于目标长度。</p>
                    <div class="dialog-actions">
                        <el-button :disabled="busy" @click="dialog = false">取消</el-button
                        ><el-button native-type="submit" type="primary" :loading="busy" :disabled="!file || !validSplit"
                            >提交导入</el-button
                        >
                    </div>
                </form>
            </el-dialog>
        </template>
        <div v-else-if="libraryLoading" class="surface empty">正在加载知识库…</div>
        <div v-else class="surface empty">
            {{ libraryError || '该知识库已不可用。' }} <el-button @click="reloadLibrary">重新加载</el-button>
        </div>
    </section>
</template>
