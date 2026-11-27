<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Search } from 'lucide-vue-next';
import { request } from '../http';
import { showError } from '../feedback';
import { librariesPath, useRead, type Library } from '../management';
import { headingLabel, strategies, type Strategy } from '../model';
type Hit = {
    chunkId: string;
    documentId: string;
    headingPath: string | null;
    content: string;
    sourceStart: number;
    sourceEnd: number;
    finalRank: number;
    vectorDistance: number | null;
    bm25Score: number | null;
    rrfScore: number | null;
    rerankScore: number | null;
};
type Result = {
    diagnostics: {
        requestedStrategy: Strategy;
        actualStrategy: Strategy | null;
        degraded: boolean;
        status: string;
        error: string | null;
    };
    chunks: Hit[];
};
const scores = [
    ['vectorDistance', '向量距离'],
    ['bm25Score', 'BM25 分数'],
    ['rrfScore', 'RRF 分数'],
    ['rerankScore', '重排分数'],
] as const;
const { data: libraries, error: libraryError } = useRead<Library[]>(ref(librariesPath));
const route = useRoute();
const library = ref('');
watch(
    [() => route.query.library, libraries],
    ([value]) => {
        library.value = libraries.value?.find((item) => item.id === value)?.id || libraries.value?.[0]?.id || '';
    },
    { immediate: true },
);
const query = ref('');
const topK = ref(5);
const compare = ref(false);
const strategy = ref<Strategy>('HYBRID_RRF');
const selected = ref<Strategy[]>(['VECTOR', 'BM25', 'HYBRID_RRF']);
const busy = ref(false);
const results = ref<Result[]>([]);
const context = ref('');
async function run() {
    if (
        busy.value ||
        !library.value ||
        !query.value.trim() ||
        !Number.isInteger(topK.value) ||
        topK.value < 1 ||
        topK.value > 100
    )
        return;
    const modes = compare.value ? [...selected.value] : [strategy.value];
    if (!modes.length) return;
    const current = library.value;
    const question = query.value.trim();
    const limit = topK.value;
    busy.value = true;
    results.value = [];
    try {
        const pool = Math.max(20, limit);
        const response = await request<{ results: Result[] }>('/api/v1/retrieval/search', {
            method: 'POST',
            body: {
                knowledgeBaseId: current,
                query: question,
                strategies: modes,
                parameters: { vectorTopK: pool, bm25TopK: pool, fusionTopK: pool, rrfK: 60, finalTopK: limit },
            },
        });
        results.value = response.results;
        context.value = question + ' · Top-' + limit;
    } catch (error) {
        showError(error);
    } finally {
        busy.value = false;
    }
}
</script>
<template>
    <section class="page">
        <header class="page-heading">
            <h1>检索工作台</h1>
            <p>测试资料召回效果，逐条检查检索证据。</p>
        </header>
        <p v-if="libraryError" role="alert" class="error">{{ libraryError }}</p>
        <form class="surface form retrieval-form" @submit.prevent="run">
            <div class="form-columns">
                <label
                    >知识库<el-select v-model="library" placeholder="选择知识库"
                        ><el-option
                            v-for="item in libraries"
                            :key="item.id"
                            :value="item.id"
                            :label="item.name" /></el-select
                ></label>
                <label>Top-K<el-input-number v-model="topK" :min="1" :max="100" :precision="0" /></label>
            </div>
            <label
                >检索问题<el-input v-model="query" placeholder="例如 Spring Boot 自动配置如何工作？" maxlength="4000"
            /></label>
            <div class="heading-row">
                <label class="inline"><el-switch v-model="compare" />多策略对比</label
                ><el-button
                    native-type="submit"
                    type="primary"
                    :icon="Search"
                    :loading="busy"
                    :disabled="!query.trim() || !library || !topK || (compare && !selected.length)"
                    >执行检索</el-button
                >
            </div>
            <label v-if="!compare"
                >检索策略<el-select v-model="strategy"
                    ><el-option v-for="mode in strategies" :key="mode" :value="mode" :label="mode" /></el-select
            ></label>
            <div v-else class="toolbar">
                <label v-for="mode in strategies" :key="mode" class="inline pill"
                    ><input v-model="selected" type="checkbox" :value="mode" />{{ mode }}</label
                >
            </div>
            <small class="muted">RRF 按候选排名融合。各策略分数仅用于解释策略内排序。</small>
        </form>
        <p v-if="busy" class="surface empty" role="status">正在检索…</p>
        <div v-else-if="results.length">
            <h2 class="results-heading">
                检索结果 <small class="muted">{{ context }}</small>
            </h2>
            <div class="result-grid" :class="{ multiple: results.length > 1 }">
                <section
                    v-for="result in results"
                    :key="result.diagnostics.requestedStrategy"
                    class="surface result-column"
                >
                    <div class="heading-row">
                        <h3>{{ result.diagnostics.requestedStrategy }}</h3>
                        <span class="pill">{{ result.chunks.length }} 条</span>
                    </div>
                    <p class="muted">
                        实际策略 {{ result.diagnostics.actualStrategy ?? '无' }} ·
                        {{ result.diagnostics.degraded ? '已降级' : '未降级' }} · {{ result.diagnostics.status }}
                    </p>
                    <p v-if="result.diagnostics.error" class="error">{{ result.diagnostics.error }}</p>
                    <p v-else-if="!result.chunks.length" class="empty">暂无匹配证据</p>
                    <article v-for="hit in result.chunks" :key="hit.chunkId" class="evidence">
                        <div class="heading-row">
                            <strong>#{{ hit.finalRank }} · 文档 {{ hit.documentId }}</strong>
                        </div>
                        <p class="muted">
                            Chunk {{ hit.chunkId }} · {{ headingLabel(hit.headingPath) }} · [{{ hit.sourceStart }},
                            {{ hit.sourceEnd }})
                        </p>
                        <div class="toolbar">
                            <template v-for="[field, label] in scores" :key="field">
                                <small v-if="hit[field] != null" class="muted"
                                    >{{ label }} {{ hit[field]?.toFixed(4) }}</small
                                >
                            </template>
                        </div>
                        <p>{{ hit.content.slice(0, 110) }}{{ hit.content.length > 110 ? '…' : '' }}</p>
                        <details>
                            <summary>展开完整证据</summary>
                            <pre>{{ hit.content }}</pre>
                        </details>
                    </article>
                </section>
            </div>
        </div>
        <div v-else class="surface empty">输入问题并执行检索，结果将在此展示。</div>
    </section>
</template>
