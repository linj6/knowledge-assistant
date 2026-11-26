<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Search } from 'lucide-vue-next';
import { api, wait } from '../api';
import { strategies, type Strategy, type Evidence } from '../model';
const route = useRoute();
const library = ref('');
watch(
    () => route.query.library,
    (value) => {
        library.value = api.libraries.find((item) => item.id === value)?.id || api.libraries[0]?.id || '';
    },
    { immediate: true },
);
const query = ref('');
const topK = ref(5);
const compare = ref(false);
const strategy = ref<Strategy>('HYBRID_RRF');
const selected = ref<Strategy[]>(['VECTOR', 'BM25', 'HYBRID_RRF']);
const busy = ref(false);
const results = ref<{ strategy: Strategy; hits: Evidence[] }[]>([]);
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
        await wait(450);
        results.value = modes.map((mode) => ({ strategy: mode, hits: api.search(current, question, limit, mode) }));
        context.value = question + ' · Top-' + limit;
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
        <form class="surface form retrieval-form" @submit.prevent="run">
            <div class="form-columns">
                <label
                    >知识库<el-select v-model="library" placeholder="选择知识库"
                        ><el-option
                            v-for="item in api.libraries"
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
                <section v-for="result in results" :key="result.strategy" class="surface result-column">
                    <div class="heading-row">
                        <h3>{{ result.strategy }}</h3>
                        <span class="pill">{{ result.hits.length }} 条</span>
                    </div>
                    <p v-if="!result.hits.length" class="empty">暂无匹配证据</p>
                    <article v-for="(hit, index) in result.hits" :key="hit.docId + hit.index" class="evidence">
                        <div class="heading-row">
                            <strong>#{{ index + 1 }} {{ hit.document }}</strong
                            ><small class="muted">{{ hit.score.toFixed(4) }}</small>
                        </div>
                        <p class="muted">Chunk {{ hit.index + 1 }} · {{ hit.heading || '正文' }}</p>
                        <p>{{ hit.text.slice(0, 110) }}{{ hit.text.length > 110 ? '…' : '' }}</p>
                        <details>
                            <summary>展开完整证据</summary>
                            <pre>{{ hit.text }}</pre>
                        </details>
                    </article>
                </section>
            </div>
        </div>
        <div v-else class="surface empty">输入问题并执行检索，结果将在此展示。</div>
    </section>
</template>
