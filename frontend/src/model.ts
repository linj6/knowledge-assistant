export const strategies = ['VECTOR', 'BM25', 'HYBRID_RRF', 'HYBRID_RERANK'] as const;
export type Strategy = (typeof strategies)[number];
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
export const dateLabel = (time: number | string) =>
    new Date(time).toLocaleString('zh-CN', { dateStyle: 'medium', timeStyle: 'short' });

export function headingLabel(path: string | null) {
    if (!path) return '正文';
    try {
        const value = JSON.parse(path) as { headings?: { text: string }[] };
        return value.headings?.map(({ text }) => text).join(' / ') || '正文';
    } catch {
        return path;
    }
}
