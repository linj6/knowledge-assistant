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
const props = defineProps<{ text: string }>();
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
const rendered = computed(() => DOMPurify.sanitize(md.render(props.text)));
function click(event: MouseEvent) {
    const target = event.target as HTMLElement;
    if (!target.closest('button[data-copy-code]')) return;
    const code = target.closest('.code-block')?.querySelector('code')?.textContent;
    if (code !== undefined && code !== null) void copy(code);
}
</script>
<template><div class="markdown" v-html="rendered" @click="click" /></template>
