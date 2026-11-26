export const strategies = ['VECTOR', 'BM25', 'HYBRID_RRF', 'HYBRID_RERANK'] as const;
export type Strategy = (typeof strategies)[number];
export const splitLabels = { FIXED: '固定长度', MARKDOWN: 'Markdown 结构感知' };
export const statusLabels = { pending: '导入中', ready: '已发布', failed: '导入失败' };
export type DocStatus = keyof typeof statusLabels;
export interface Library {
    id: string;
    name: string;
    description: string;
    updated: number;
    deleted?: boolean;
}
export interface Split {
    mode: keyof typeof splitLabels;
    size: number;
    overlap: number;
}
export interface Chunk {
    text: string;
    heading: string;
}
export interface Doc {
    id: string;
    library: string;
    name: string;
    source: string;
    split: Split;
    status: DocStatus;
    chunks: Chunk[];
    updated: number;
    attempts: { date: number; status: DocStatus; reason?: string }[];
    completeAt?: number;
}
export interface Evidence extends Chunk {
    docId: string;
    document: string;
    index: number;
    score: number;
}
export const memoryTypes = {
    BACKGROUND: '技术背景',
    LEARNING_GOAL: '学习目标',
    ANSWER_PREFERENCE: '回答偏好',
    TECH_PREFERENCE: '技术偏好',
    LEARNING_STATUS: '学习情况',
    OTHER: '其他',
};
export type MemoryType = keyof typeof memoryTypes;
export const isGlobal = (type: MemoryType) => type === 'BACKGROUND' || type === 'ANSWER_PREFERENCE';
export interface Memory {
    id: string;
    type: MemoryType;
    text: string;
    keywords: string;
    enabled: boolean;
    updated: number;
}
export interface Turn {
    id: string;
    question: string;
    strategy: Strategy;
    status: 'pending' | 'ready' | 'failed' | 'cancelled';
    answer?: string;
    evidence?: Evidence[];
    trace?: Pick<Memory, 'id' | 'type'>[];
}
export interface Conversation {
    id: string;
    library: string;
    title: string;
    updated: number;
    turns: Turn[];
}
export const defaults: Split = { mode: 'FIXED', size: 800, overlap: 120 };
export const dateLabel = (time: number) =>
    new Date(time).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' });
