<script setup lang="ts">
import { computed } from 'vue';
import MarkdownIt from 'markdown-it';
import DOMPurify from 'dompurify';
import hljs from 'highlight.js/lib/core';
import java from 'highlight.js/lib/languages/java';
import yaml from 'highlight.js/lib/languages/yaml';
import json from 'highlight.js/lib/languages/json';
import 'highlight.js/styles/github.css';
import { copy } from './feedback';

hljs.registerLanguage('java', java);
hljs.registerLanguage('yaml', yaml);
hljs.registerLanguage('json', json);
const props = defineProps<{ text: string; citationIds?: string[] }>();
const emit = defineEmits<{ citation: [id: string] }>();
const md = new MarkdownIt({ html: false, linkify: true });
md.renderer.rules.fence = (tokens, index) => {
    const token = tokens[index];
    const language = token.info.trim().split(/\s+/)[0];
    const code = hljs.getLanguage(language)
        ? hljs.highlight(token.content, { language }).value
        : md.utils.escapeHtml(token.content);
    return (
        '<div class="code-block"><div class="code-toolbar"><span>' +
        md.utils.escapeHtml(language || '文本') +
        '</span><button type="button" data-copy-code>复制代码</button></div><pre><code>' +
        code +
        '</code></pre></div>'
    );
};
md.renderer.rules.text = (tokens, index, _options, env) => {
    const text = md.utils.escapeHtml(tokens[index].content);
    return text.replace(/\[(E\d+)\]/g, (marker, id: string) =>
        env.citationIds?.includes(id)
            ? '<button type="button" class="text-btn" data-citation="' + id + '">' + marker + '</button>'
            : marker,
    );
};
const rendered = computed(() => DOMPurify.sanitize(md.render(props.text, { citationIds: props.citationIds })));
function click(event: MouseEvent) {
    const target = event.target as HTMLElement;
    const citation = target.closest<HTMLElement>('button[data-citation]')?.dataset.citation;
    if (citation && props.citationIds?.includes(citation)) {
        event.preventDefault();
        emit('citation', citation);
        return;
    }
    if (!target.closest('button[data-copy-code]')) return;
    const code = target.closest('.code-block')?.querySelector('code')?.textContent;
    if (code !== undefined && code !== null) void copy(code);
}
</script>
<template><div class="markdown" v-html="rendered" @click="click" /></template>
