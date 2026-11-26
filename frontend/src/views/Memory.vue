<script setup lang="ts">
import { computed, ref } from 'vue';
import { Brain, Plus } from 'lucide-vue-next';
import { ElMessage } from 'element-plus';
import { api } from '../api';
import { confirmDelete, showError } from '../feedback';
import { dateLabel, isGlobal, memoryTypes, type Memory } from '../model';
const items = computed(() => [...api.memories].sort((a, b) => b.updated - a.updated));
const dialog = ref(false);
const editing = ref<Memory>();
const form = ref({ type: 'ANSWER_PREFERENCE' as Memory['type'], text: '', keywords: '', enabled: true });
function open(item?: Memory) {
    editing.value = item;
    form.value = item
        ? { type: item.type, text: item.text, keywords: item.keywords, enabled: item.enabled }
        : { type: 'ANSWER_PREFERENCE', text: '', keywords: '', enabled: true };
    dialog.value = true;
}
function save() {
    try {
        api.saveMemory(form.value, editing.value);
        dialog.value = false;
        ElMessage.success('记忆已保存');
    } catch (error) {
        showError(error);
    }
}
function toggle(item: Memory, enabled: boolean) {
    try {
        api.saveMemory({ ...item, enabled }, item);
    } catch (error) {
        showError(error);
    }
}
async function remove(item: Memory) {
    if (await confirmDelete('这条记忆')) {
        api.deleteMemory(item);
        ElMessage.success('记忆已删除');
    }
}
</script>
<template>
    <section class="page">
        <header class="page-heading heading-row">
            <div>
                <h1>长期记忆</h1>
                <p>管理跨会话使用的个人背景与回答偏好。</p>
            </div>
            <el-button type="primary" :icon="Plus" :disabled="items.length >= 50" @click="open()">新增记忆</el-button>
        </header>
        <section class="surface memory-setting heading-row">
            <div class="toolbar">
                <div class="tile-icon"><Brain :size="23" /></div>
                <div>
                    <h2>账号记忆开关</h2>
                    <p class="muted">启用后，问答选中的相关记忆可能发送给线上模型。</p>
                </div>
            </div>
            <el-switch v-model="api.memoryEnabled" aria-label="账号记忆开关" />
        </section>
        <div class="heading-row results-heading">
            <h2>已保存记忆</h2>
            <span class="muted">{{ items.length }} / 50 条</span>
        </div>
        <div v-if="!items.length" class="surface empty">暂无记忆，可手动新增一条。</div>
        <article v-for="item in items" :key="item.id" class="surface memory-card">
            <div class="heading-row">
                <span class="pill">{{ isGlobal(item.type) ? '全局型' : '主题型' }} · {{ memoryTypes[item.type] }}</span>
                <el-switch
                    :model-value="item.enabled"
                    :aria-label="'启用记忆 ' + item.text"
                    @change="toggle(item, Boolean($event))"
                />
            </div>
            <p class="memory-text">{{ item.text }}</p>
            <div v-if="!isGlobal(item.type)" class="toolbar">
                <small class="muted">主题关键词</small
                ><span
                    v-for="(keyword, index) in item.keywords
                        .split(',')
                        .map((word) => word.trim())
                        .filter(Boolean)"
                    :key="index"
                    class="keyword"
                    >{{ keyword }}</span
                ><span v-if="!item.keywords" class="muted">未设置</span>
            </div>
            <footer class="heading-row">
                <small class="muted"
                    >更新于 {{ dateLabel(item.updated) }} · {{ item.enabled ? '已启用' : '已停用' }}</small
                >
                <div class="toolbar">
                    <button class="text-btn" @click="open(item)">编辑</button
                    ><button class="text-btn danger" @click="remove(item)">删除</button>
                </div>
            </footer>
        </article>
        <el-dialog v-model="dialog" :title="editing ? '编辑记忆' : '新增记忆'" width="510px">
            <form class="form" @submit.prevent="save">
                <label
                    >类型<el-select v-model="form.type"
                        ><el-option
                            v-for="(label, type) in memoryTypes"
                            :key="type"
                            :value="type"
                            :label="label + (isGlobal(type) ? ' · 全局型' : ' · 主题型')" /></el-select
                ></label>
                <label
                    >记忆正文<el-input v-model="form.text" type="textarea" :rows="4" /><small class="muted"
                        >{{ [...form.text.trim()].length }} / 500 字符</small
                    ></label
                >
                <label v-if="!isGlobal(form.type)"
                    >主题关键词<el-input v-model="form.keywords" placeholder="用逗号分隔关键词" /><small class="muted"
                        >{{ [...form.keywords.trim()].length }} / 200 字符</small
                    ></label
                >
                <label class="inline"><el-switch v-model="form.enabled" />启用此条记忆</label>
                <div class="dialog-actions">
                    <el-button @click="dialog = false">取消</el-button
                    ><el-button native-type="submit" type="primary" :disabled="!form.text.trim()">保存</el-button>
                </div>
            </form>
        </el-dialog>
    </section>
</template>
