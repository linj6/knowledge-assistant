export const AppState = {
    activePage: 'chat',
    selectedKnowledgeBaseId: '',
    knowledgeBases: [],
    initialized: false,
};
const listeners = new Set();
export function subscribe(listener) {
    listeners.add(listener);
    return () => listeners.delete(listener);
}
export function updateState(patch) {
    Object.assign(AppState, patch);
    listeners.forEach((listener) => listener(AppState));
}
