<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { FileText, MessageSquare, Search, UploadCloud } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import { confirmDelete, showError } from '../feedback';
import { dateLabel, defaults, splitLabels, statusLabels, type Doc, type DocStatus, type Split } from '../model';
const route = useRoute();
const library = computed(() => api.libraries.find((item) => item.id === route.params.id));
const query = ref('');
const status = ref<DocStatus | ''>('');
const page = ref(1);
const pageSize = 5;
const records = computed(() =>
    api.documents
        .filter(
            (doc) =>
                doc.library === library.value?.id &&
                (!status.value || doc.status === status.value) &&
                doc.name.toLowerCase().includes(query.value.trim().toLowerCase()),
        )
        .sort((a, b) => b.updated - a.updated),
);
const pageRecords = computed(() => records.value.slice((page.value - 1) * pageSize, page.value * pageSize));
watch([query, status, library], () => {
    page.value = 1;
});
watch(
    () => records.value.length,
    (count) => {
        page.value = Math.min(page.value, Math.max(1, Math.ceil(count / pageSize)));
    },
);

const dialog = ref(false);
const file = ref<File>();
const split = ref<Split>({ ...defaults });
const busy = ref(false);
const dragging = ref(false);
function open() {
    file.value = undefined;
    split.value = { ...defaults };
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
    if (!file.value || !library.value || busy.value) return;
    busy.value = true;
    try {
        await api.upload(library.value.id, file.value, split.value);
        query.value = '';
        status.value = '';
        page.value = 1;
        dialog.value = false;
        ElMessage.success('已提交导入任务');
    } catch (error) {
        showError(error);
    } finally {
        busy.value = false;
    }
}
async function remove(doc: Doc) {
    if (await confirmDelete(doc.name)) {
        api.deleteDocument(doc);
        ElMessage.success('文档已删除');
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
                    <small class="muted">更新于 {{ dateLabel(library.updated) }}</small>
                </div>
                <div class="toolbar">
                    <RouterLink class="action-link" :to="{ path: '/retrieval', query: { library: library.id } }"
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
                                v-for="(label, key) in statusLabels"
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
                        <tr v-for="doc in pageRecords" :key="doc.id">
                            <td>
                                <RouterLink :to="'/libraries/' + library.id + '/documents/' + doc.id"
                                    ><FileText :size="15" />{{ doc.name }}</RouterLink
                                >
                            </td>
                            <td>
                                <span class="status" :class="doc.status">{{ statusLabels[doc.status] }}</span>
                            </td>
                            <td>{{ splitLabels[doc.split.mode] }}</td>
                            <td>{{ doc.chunks.length }}</td>
                            <td>{{ dateLabel(doc.updated) }}</td>
                            <td>
                                <div class="toolbar">
                                    <button
                                        v-if="doc.status === 'failed'"
                                        class="text-btn"
                                        @click="api.retryDocument(doc)"
                                    >
                                        重试</button
                                    ><button class="text-btn danger" @click="remove(doc)">删除</button>
                                </div>
                            </td>
                        </tr>
                    </tbody>
                </table>
                <div v-if="!records.length" class="empty">没有匹配的文档</div>
                <el-pagination
                    v-else
                    v-model:current-page="page"
                    :page-size="pageSize"
                    :total="records.length"
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
                        >切分策略<el-select v-model="split.mode" :disabled="busy"
                            ><el-option
                                v-for="(label, key) in splitLabels"
                                :key="key"
                                :value="key"
                                :label="label" /></el-select
                    ></label>
                    <p class="muted">
                        {{
                            split.mode === 'FIXED'
                                ? '按目标长度切分，重叠部分保留相邻片段的上下文。'
                                : '优先沿 Markdown 标题结构切分，超长段落继续按目标长度拆分。'
                        }}
                    </p>
                    <div class="form-columns">
                        <label
                            >Chunk 目标长度<el-input-number
                                v-model="split.size"
                                :min="1"
                                :max="4000"
                                :precision="0"
                                :disabled="busy"
                        /></label>
                        <label
                            >重叠长度<el-input-number
                                v-model="split.overlap"
                                :min="0"
                                :max="3999"
                                :precision="0"
                                :disabled="busy"
                        /></label>
                    </div>
                    <p v-if="split.overlap >= split.size" class="error">重叠长度必须小于目标长度。</p>
                    <div class="dialog-actions">
                        <el-button :disabled="busy" @click="dialog = false">取消</el-button
                        ><el-button
                            native-type="submit"
                            type="primary"
                            :loading="busy"
                            :disabled="!file || !split.size || split.overlap == null || split.overlap >= split.size"
                            >提交导入</el-button
                        >
                    </div>
                </form>
            </el-dialog>
        </template>
        <div v-else class="surface empty">该知识库已不可用。</div>
    </section>
</template>
