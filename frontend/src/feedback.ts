import { ElMessage, ElMessageBox } from 'element-plus';

export function showError(error: unknown) {
    ElMessage.error(error instanceof Error ? error.message : '操作失败，请重试。');
}
export async function confirmDelete(name: string, note = '') {
    try {
        await ElMessageBox.confirm('确定删除「' + name + '」？' + note, '确认删除', {
            type: 'warning',
            confirmButtonText: '删除',
            cancelButtonText: '取消',
        });
        return true;
    } catch {
        return false;
    }
}
export async function copy(text: string) {
    try {
        await navigator.clipboard.writeText(text);
        ElMessage.success('已复制');
    } catch {
        ElMessage.error('复制失败，请手动选择文本。');
    }
}
