<script setup lang="ts">
import { computed, ref } from 'vue';
import { BookOpen, FileText, Plus, Search } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import { confirmDelete, showError } from '../feedback';
import { dateLabel, type Library } from '../model';

const query = ref('');
const dialog = ref(false);
const editing = ref<Library>();
const name = ref('');
const description = ref('');
const filtered = computed(() =>
    api.libraries.filter((item) =>
        (item.name + item.description).toLowerCase().includes(query.value.trim().toLowerCase()),
    ),
);
function open(item?: Library) {
    editing.value = item;
    name.value = item?.name || '';
    description.value = item?.description || '';
    dialog.value = true;
}
function save() {
    try {
        api.saveLibrary({ name: name.value, description: description.value }, editing.value);
        dialog.value = false;
        ElMessage.success('知识库已保存');
    } catch (error) {
        showError(error);
    }
}
async function remove(item: Library) {
    if (await confirmDelete(item.name, '删除后，该知识库将无法用于检索或继续问答。')) {
        api.deleteLibrary(item);
        ElMessage.success('知识库已删除');
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
                    <span
                        ><FileText :size="15" />{{
                            api.documents.filter((doc) => doc.library === item.id).length
                        }}
                        份文档</span
                    ><span>{{ dateLabel(item.updated) }}</span>
                </footer>
            </article>
        </div>
        <div v-if="!filtered.length" class="surface empty">
            {{ query ? '没有匹配的知识库' : '创建第一个知识库以导入资料' }}
        </div>
        <el-dialog v-model="dialog" :title="editing ? '编辑知识库' : '创建知识库'" width="480px">
            <form class="form" @submit.prevent="save">
                <label>名称<el-input v-model="name" maxlength="80" /></label>
                <label>描述<el-input v-model="description" type="textarea" :rows="3" maxlength="500" /></label>
                <div class="dialog-actions">
                    <el-button @click="dialog = false">取消</el-button
                    ><el-button native-type="submit" type="primary" :disabled="!name.trim()">保存</el-button>
                </div>
            </form>
        </el-dialog>
    </section>
</template>
