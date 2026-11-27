import { ref, shallowRef, watch, type Ref } from 'vue';
import { errorMessage, request } from './http';

export const librariesPath = '/api/v1/knowledge-bases';
export const taskPath = (id: string) => '/api/v1/import-tasks/' + id;

export type Library = {
    id: string;
    name: string;
    description: string | null;
    updatedAt: string;
    documentCount: number;
};
export const splitLabels = {
    FIXED_CODE_POINT: '固定长度',
    MARKDOWN_STRUCTURE_AWARE: 'Markdown 结构感知',
};
export type Strategy = keyof typeof splitLabels;
export type Chunking = { strategy: Strategy; chunkSize: number; chunkOverlap: number };
export type Task = {
    importTaskId: string;
    documentId: string;
    status: 'RECEIVED' | 'PROCESSING' | 'READY' | 'FAILED';
    createdAt: string;
    updatedAt: string;
    failureCode: string | null;
};
export type DocumentRecord = {
    documentId: string;
    filename: string;
    createdAt: string;
    publishedAt: string | null;
    activeVersionId: string | null;
    chunkCount: string;
    chunkingConfig: Chunking | null;
    importTask: Task | null;
};
export type Page<T> = { items: T[]; total: string };
export type Chunk = {
    chunkId: string;
    chunkOrder: number;
    headingPath: string | null;
    content: string;
    sourceStart: number | null;
    sourceEnd: number | null;
};
export const statusLabels = {
    RECEIVED: '等待导入',
    PROCESSING: '导入中',
    READY: '已发布',
    FAILED: '失败',
    UNPUBLISHED: '未发布',
};
export const documentStatus = (doc: DocumentRecord) =>
    doc.importTask?.status ?? (doc.activeVersionId ? 'READY' : 'UNPUBLISHED');
export const statusClass = (doc: DocumentRecord) => {
    const status = documentStatus(doc);
    return status === 'RECEIVED' || status === 'PROCESSING' ? 'pending' : status.toLowerCase();
};
export const failureLabel = (task: Task | null) =>
    task?.failureCode === 'IMPORT_FAILED' ? '导入失败（IMPORT_FAILED）' : task?.failureCode;

export function useRead<T>(path: Readonly<Ref<string>>, delay = 0) {
    const data = shallowRef<T>();
    const loading = ref(false);
    const error = ref('');
    const revision = ref(0);
    watch(
        [path, revision],
        ([url], previous, cleanup) => {
            if (url !== previous?.[0]) data.value = undefined;
            error.value = '';
            loading.value = Boolean(url);
            if (!url) return;
            const controller = new AbortController();
            const signal = controller.signal;
            const timer = window.setTimeout(async () => {
                try {
                    const result = await request<T>(url, { signal });
                    if (!signal.aborted) data.value = result;
                } catch (failure) {
                    if (!signal.aborted) error.value = errorMessage(failure);
                } finally {
                    if (!signal.aborted) loading.value = false;
                }
            }, delay);
            cleanup(() => {
                window.clearTimeout(timer);
                controller.abort();
            });
        },
        { immediate: true, flush: 'sync' },
    );
    return { data, loading, error, reload: () => revision.value++ };
}

export function useTaskPolling(tasks: Readonly<Ref<Task[]>>, refresh: () => void) {
    const error = ref('');
    const revision = ref(0);
    watch(
        [tasks, revision],
        ([records], _, cleanup) => {
            error.value = '';
            const isActive = ({ status }: Task) => status === 'RECEIVED' || status === 'PROCESSING';
            let active = records.filter(isActive);
            if (!active.length) return;
            const controller = new AbortController();
            const signal = controller.signal;
            let timer: number;
            async function poll() {
                try {
                    const current = await Promise.all(
                        active.map(({ importTaskId }) => request<Task>(taskPath(importTaskId), { signal })),
                    );
                    if (signal.aborted) return;
                    if (current.some((task, index) => task.status !== active[index].status)) {
                        refresh();
                    }
                    active = current.filter(isActive);
                    if (active.length) timer = window.setTimeout(poll, 2000);
                } catch (failure) {
                    if (!signal.aborted) error.value = errorMessage(failure);
                }
            }
            timer = window.setTimeout(poll, 2000);
            cleanup(() => {
                window.clearTimeout(timer);
                controller.abort();
            });
        },
        { immediate: true },
    );
    return {
        pollError: error,
        resumePolling: () => {
            refresh();
            revision.value++;
        },
    };
}
