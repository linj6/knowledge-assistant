<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '../api';
import { dateLabel, splitLabels, statusLabels } from '../model';

const route = useRoute();
const library = computed(() => api.libraries.find((item) => item.id === route.params.id));
const doc = computed(() =>
    api.documents.find((item) => item.id === route.params.doc && item.library === library.value?.id),
);
const tab = ref('source');
const page = ref(1);
const pageSize = 4;
const chunks = computed(() => doc.value?.chunks.slice((page.value - 1) * pageSize, page.value * pageSize) || []);
watch(doc, () => {
    page.value = 1;
    tab.value = 'source';
});
</script>
<template>
    <section class="page">
        <RouterLink class="breadcrumb" :to="library ? '/libraries/' + library.id : '/libraries'">{{
            library?.name || '知识库'
        }}</RouterLink>
        <template v-if="doc">
            <header class="page-heading heading-row">
                <div>
                    <h1>{{ doc.name }}</h1>
                    <p>更新于 {{ dateLabel(doc.updated) }}</p>
                </div>
                <el-button v-if="doc.status === 'failed'" type="primary" @click="api.retryDocument(doc)"
                    >重试导入</el-button
                >
            </header>
            <div class="surface facts">
                <div>
                    <span>导入状态</span
                    ><strong class="status" :class="doc.status">{{ statusLabels[doc.status] }}</strong>
                </div>
                <div>
                    <span>实际切分策略</span><strong>{{ splitLabels[doc.split.mode] }}</strong>
                </div>
                <div>
                    <span>目标长度 / 重叠</span><strong>{{ doc.split.size }} / {{ doc.split.overlap }}</strong>
                </div>
                <div>
                    <span>索引版本</span><strong>{{ doc.status === 'ready' ? 'v1 · 已发布' : '未发布' }}</strong>
                </div>
                <div>
                    <span>Chunk 数量</span><strong>{{ doc.chunks.length }}</strong>
                </div>
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
                <pre v-if="tab === 'source'" class="source">{{ doc.source }}</pre>
                <div v-else-if="tab === 'chunks'" class="panel-body">
                    <p v-if="!chunks.length" class="empty">暂无已发布 Chunk</p>
                    <article v-for="(chunk, index) in chunks" :key="index" class="surface evidence">
                        <strong>Chunk {{ (page - 1) * pageSize + index + 1 }}</strong>
                        <p class="muted">{{ chunk.heading || '正文' }}</p>
                        <pre>{{ chunk.text }}</pre>
                    </article>
                    <el-pagination
                        v-if="chunks.length"
                        v-model:current-page="page"
                        :page-size="pageSize"
                        :total="doc.chunks.length"
                        layout="prev, pager, next, total"
                    />
                </div>
                <div v-else class="panel-body">
                    <article v-for="(item, index) in [...doc.attempts].reverse()" :key="index" class="surface evidence">
                        <div class="heading-row">
                            <strong>第 {{ doc.attempts.length - index }} 次导入</strong
                            ><span class="status" :class="item.status">{{ statusLabels[item.status] }}</span>
                        </div>
                        <p class="muted">{{ dateLabel(item.date) }}</p>
                        <p v-if="item.reason" class="error">{{ item.reason }}</p>
                    </article>
                </div>
            </div>
        </template>
        <div v-else class="surface empty">该文档已不可用。</div>
    </section>
</template>
