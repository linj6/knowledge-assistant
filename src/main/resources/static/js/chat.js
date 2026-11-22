import { initializeConversations } from './conversations.js';
import { createEvidence } from './evidence.js';
let initialized = false;
export function initializeChat() {
    if (initialized) return;
    initialized = true;
    const root = document.querySelector('#chat');
    const node = (tag, text) => {
        const el = document.createElement(tag);
        if (text !== undefined) el.textContent = text;
        return el;
    };
    root.replaceChildren(node('h1', 'AI 问答'));
    const context = node('p');
    context.className = 'context';
    root.append(context);
    const evidence = createEvidence(root);
    function appendCitations(item, selectedKb, citations) {
        for (const c of citations) {
            const button = node('button', '引用：' + c.evidenceId + ' · 查看证据');
            button.className = 'citation-button';
            button.type = 'button';
            button.addEventListener('click', () => evidence.open(selectedKb, c, button));
            item.append(button);
        }
    }
    initializeConversations(root, node, evidence, appendCitations);
}
