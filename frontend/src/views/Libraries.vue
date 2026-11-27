<script setup lang="ts">
import { computed, ref } from 'vue';
import { BookOpen, FileText, Plus, Search } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { errorMessage, request } from '../http';
import { librariesPath, useRead, type Library } from '../management';
import { confirmDelete } from '../feedback';
import { dateLabel } from '../model';

const query = ref('');
const dialog = ref(false);
const editing = ref<Library>();
const name = ref('');
const description = ref('');
const busy = ref(false);
const deleting = ref(false);
const path = computed(() => librariesPath + '?' + new URLSearchParams({ keyword: query.value.trim() }));
const { data: filtered, loading, error, reload } = useRead<Library[]>(path, 200);
function open(item?: Library) {
    editing.value = item;
    name.value = item?.name || '';
    description.value = item?.description || '';
    dialog.value = true;
}
async function save() {
    if (busy.value || !name.value.trim()) return;
    busy.value = true;
    try {
        await request(editing.value ? librariesPath + '/' + editing.value.id : librariesPath, {
            method: editing.value ? 'PUT' : 'POST',
            body: { name: name.value, description: description.value },
        });
        dialog.value = false;
        reload();
        ElMessage.success('知识库已保存');
    } catch (failure) {
        ElMessage.error(errorMessage(failure));
    } finally {
        busy.value = false;
    }
}
async function remove(item: Library) {
    if (deleting.value) return;
    deleting.value = true;
    try {
        if (!(await confirmDelete(item.name, '删除后，该知识库将无法用于检索或继续问答。'))) return;
        await request(librariesPath + '/' + item.id, { method: 'DELETE' });
        reload();
        ElMessage.success('知识库已删除');
    } catch (failure) {
        ElMessage.error(errorMessage(failure));
    } finally {
        deleting.value = false;
    }
}
</script>
<template>
    <section class="page">
        <header class="page-heading heading-row">
            <div>
                <h1>知识库</h1>
                <p>整理学习资料，为问答与检索提供证据。</p>
            </div>
            <el-button type="primary" :icon="Plus" @click="open()">创建知识库</el-button>
        </header>
        <el-input v-model="query" class="search" placeholder="搜索知识库" :prefix-icon="Search" clearable />
        <div class="library-grid">
            <article v-for="item in filtered" :key="item.id" class="surface library-card">
                <RouterLink class="card-link" :to="'/libraries/' + item.id" :aria-label="'打开' + item.name" />
                <div class="heading-row">
                    <div class="tile-icon"><BookOpen :size="21" /></div>
                    <details class="menu">
                        <summary :aria-label="'知识库操作 ' + item.name">⋯</summary>
                        <div>
                            <button
                                @click="
                                    open(item);
                                    ($event.target as HTMLElement).closest('details')?.removeAttribute('open');
                                "
                            >
                                编辑</button
                            ><button class="danger" @click="remove(item)">删除</button>
                        </div>
                    </details>
                </div>
                <h2>{{ item.name }}</h2>
                <p>{{ item.description || '暂无描述' }}</p>
                <footer>
                    <span><FileText :size="15" />{{ item.documentCount }} 份文档</span
                    ><span>{{ dateLabel(Date.parse(item.updatedAt)) }}</span>
                </footer>
            </article>
        </div>
        <div v-if="loading" class="surface empty">正在加载知识库…</div>
        <div v-else-if="error" class="surface empty">{{ error }} <el-button @click="reload">重新加载</el-button></div>
        <div v-else-if="!filtered?.length" class="surface empty">
            {{ query ? '没有匹配的知识库' : '创建第一个知识库以导入资料' }}
        </div>
        <el-dialog
            v-model="dialog"
            :title="editing ? '编辑知识库' : '创建知识库'"
            width="480px"
            :close-on-click-modal="!busy"
            :close-on-press-escape="!busy"
            :show-close="!busy"
        >
            <form class="form" @submit.prevent="save">
                <label>名称<el-input v-model="name" maxlength="100" /></label>
                <label>描述<el-input v-model="description" type="textarea" :rows="3" maxlength="1000" /></label>
                <div class="dialog-actions">
                    <el-button :disabled="busy" @click="dialog = false">取消</el-button
                    ><el-button native-type="submit" type="primary" :disabled="!name.trim()" :loading="busy"
                        >保存</el-button
                    >
                </div>
            </form>
        </el-dialog>
    </section>
</template>
