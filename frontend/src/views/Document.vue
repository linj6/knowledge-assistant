<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ApiError, errorMessage, request } from '../http';
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
    type Task,
    type Chunk,
    type Page,
} from '../management';

const route = useRoute();
const libraryPath = computed(() => (route.params.doc ? librariesPath + '/' + route.params.id : ''));
const path = computed(() => (libraryPath.value ? libraryPath.value + '/documents/' + route.params.doc : ''));
const { data: library } = useRead<Library>(libraryPath);
const { data: doc, loading, error, reload } = useRead<DocumentRecord>(path);
const tab = ref('source');
const page = ref(1);
const pageSize = 4;
watch(
    path,
    () => {
        page.value = 1;
        tab.value = 'source';
    },
    { flush: 'sync' },
);
const sourcePath = computed(() => (doc.value && tab.value === 'source' ? path.value + '/content' : ''));
const chunkPath = computed(() =>
    doc.value && tab.value === 'chunks' ? path.value + '/chunks?page=' + page.value + '&pageSize=' + pageSize : '',
);
const source = useRead<{ content: string }>(sourcePath);
const chunks = useRead<Page<Chunk>>(chunkPath);
watch(
    () => doc.value?.activeVersionId,
    () => {
        page.value = 1;
        if (chunkPath.value) chunks.reload();
    },
);
const tasks = computed(() => (doc.value?.importTask ? [doc.value.importTask] : []));
const { pollError, resumePolling } = useTaskPolling(tasks, reload);
const busy = ref(false);
async function retry() {
    const task = doc.value?.importTask;
    if (!task || busy.value) return;
    const url = path.value;
    busy.value = true;
    try {
        await request<Task>(taskPath(task.importTaskId) + ':retry', { method: 'POST' });
        if (url !== path.value) return;
        ElMessage.success('已提交重试');
        reload();
    } catch (failure) {
        if (url !== path.value) return;
        ElMessage.error(errorMessage(failure));
        if (failure instanceof ApiError && failure.status === 409) reload();
    } finally {
        busy.value = false;
    }
}
function headingLabel(path: string | null) {
    if (!path) return '正文';
    try {
        const value = JSON.parse(path) as { headings?: { text: string }[] };
        return value.headings?.map(({ text }) => text).join(' / ') || '正文';
    } catch {
        return path;
    }
}
</script>
<template>
    <section class="page">
        <RouterLink class="breadcrumb" :to="'/libraries/' + route.params.id">{{
            library?.name || '知识库'
        }}</RouterLink>
        <template v-if="doc">
            <header class="page-heading heading-row">
                <div>
                    <h1>{{ doc.filename }}</h1>
                    <p>
                        更新于
                        {{ dateLabel(Date.parse(doc.importTask?.updatedAt ?? doc.publishedAt ?? doc.createdAt)) }}
                    </p>
                </div>
                <el-button v-if="documentStatus(doc) === 'FAILED'" type="primary" :loading="busy" @click="retry"
                    >重试导入</el-button
                >
            </header>
            <div class="surface facts">
                <div>
                    <span>导入状态</span
                    ><strong class="status" :class="statusClass(doc)">{{ statusLabels[documentStatus(doc)] }}</strong>
                </div>
                <div>
                    <span>实际切分策略</span
                    ><strong>{{ doc.chunkingConfig ? splitLabels[doc.chunkingConfig.strategy] : '—' }}</strong>
                </div>
                <div>
                    <span>目标长度 / 重叠</span
                    ><strong>{{
                        doc.chunkingConfig
                            ? doc.chunkingConfig.chunkSize + ' / ' + doc.chunkingConfig.chunkOverlap
                            : '—'
                    }}</strong>
                </div>
                <div>
                    <span>索引版本</span
                    ><strong>{{ doc.activeVersionId ? doc.activeVersionId + ' · 已发布' : '未发布' }}</strong>
                </div>
                <div>
                    <span>Chunk 数量</span><strong>{{ doc.chunkCount }}</strong>
                </div>
            </div>
            <div v-if="pollError || error" class="surface empty">
                {{ pollError || error }} <el-button @click="resumePolling">重新加载</el-button>
            </div>
            <div class="surface table-panel">
                <div class="tabs" role="group" aria-label="文档详情">
                    <button
                        v-for="(label, key) in { source: '原文预览', chunks: 'Chunk 预览', history: '导入记录' }"
                        :key="key"
                        :class="{ selected: tab === key }"
                        :aria-pressed="tab === key"
                        @click="tab = key"
                    >
                        {{ label }}
                    </button>
                </div>
                <template v-if="tab === 'source'">
                    <p v-if="source.loading.value" class="empty">正在加载原文…</p>
                    <div v-else-if="source.error.value" class="empty">
                        {{ source.error.value }} <el-button @click="source.reload">重新加载</el-button>
                    </div>
                    <pre v-else class="source">{{ source.data.value?.content }}</pre>
                </template>
                <div v-else-if="tab === 'chunks'" class="panel-body">
                    <p v-if="chunks.loading.value" class="empty">正在加载 Chunk…</p>
                    <div v-else-if="chunks.error.value" class="empty">
                        {{ chunks.error.value }} <el-button @click="chunks.reload">重新加载</el-button>
                    </div>
                    <p v-else-if="!chunks.data.value?.items.length" class="empty">暂无已发布 Chunk</p>
                    <article v-for="chunk in chunks.data.value?.items" :key="chunk.chunkId" class="surface evidence">
                        <strong>Chunk {{ chunk.chunkOrder + 1 }}</strong>
                        <p class="muted">{{ headingLabel(chunk.headingPath) }}</p>
                        <p v-if="chunk.sourceStart != null && chunk.sourceEnd != null" class="muted">
                            原文码点区间 [{{ chunk.sourceStart }}, {{ chunk.sourceEnd }})
                        </p>
                        <pre>{{ chunk.content }}</pre>
                    </article>
                    <el-pagination
                        v-if="Number(chunks.data.value?.total ?? 0) > 0"
                        v-model:current-page="page"
                        :page-size="pageSize"
                        :total="Number(chunks.data.value?.total ?? 0)"
                        layout="prev, pager, next, total"
                    />
                </div>
                <div v-else class="panel-body">
                    <article v-if="doc.importTask" class="surface evidence">
                        <div class="heading-row">
                            <strong>导入任务 {{ doc.importTask.importTaskId }}</strong>
                            <span class="status" :class="statusClass(doc)">{{
                                statusLabels[doc.importTask.status]
                            }}</span>
                        </div>
                        <p class="muted">
                            创建于 {{ dateLabel(Date.parse(doc.importTask.createdAt)) }} · 更新于
                            {{ dateLabel(Date.parse(doc.importTask.updatedAt)) }}
                        </p>
                        <p v-if="failureLabel(doc.importTask)" class="error">{{ failureLabel(doc.importTask) }}</p>
                    </article>
                    <p v-else class="empty">暂无导入记录</p>
                </div>
            </div>
        </template>
        <div v-else-if="loading" class="surface empty">正在加载文档…</div>
        <div v-else class="surface empty">
            {{ error || '该文档已不可用。' }} <el-button @click="reload">重新加载</el-button>
        </div>
    </section>
</template>
