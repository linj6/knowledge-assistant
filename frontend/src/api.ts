import { reactive, ref, watch } from 'vue';
import * as seed from './fixtures';
import {
    isGlobal,
    type Library,
    type Doc,
    type Split,
    type Memory,
    type Conversation,
    type Turn,
    type Strategy,
} from './model';

const id = () => crypto.randomUUID();
export const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));
const initial = () => ({
    libraries: structuredClone(seed.libraries),
    documents: structuredClone(seed.documents),
    memories: structuredClone(seed.memories),
    conversations: structuredClone(seed.conversations),
    memoryEnabled: false,
});
type Data = ReturnType<typeof initial>;
const prefix = 'ka-v2:';
export const account = ref(sessionStorage.getItem(prefix + 'session') || '');
const data = reactive(initial());
const storageKey = (user: string) => prefix + 'data:' + user;

function load(user: string) {
    let saved: Data | undefined;
    try {
        saved = JSON.parse(localStorage.getItem(storageKey(user)) || 'null') || undefined;
    } catch {
        /* 使用样例数据 */
    }
    Object.assign(data, saved || initial());
    for (const chat of data.conversations) {
        for (const turn of chat.turns) if (turn.status === 'pending') turn.status = 'cancelled';
    }
    for (const doc of data.documents) if (doc.status === 'pending') schedule(doc);
}
if (account.value) load(account.value);
watch(
    data,
    () => {
        if (account.value) {
            try {
                localStorage.setItem(storageKey(account.value), JSON.stringify(data));
            } catch {
                /* 超出浏览器配额时保留本次会话数据 */
            }
        }
    },
    { flush: 'sync' },
);

function schedule(doc: Doc) {
    setTimeout(
        () => {
            if (!data.documents.includes(doc) || doc.status !== 'pending') return;
            const chunks = [];
            let heading = '';
            const groups = doc.split.mode === 'MARKDOWN' ? doc.source.split(/(?=^#{1,6}\s)/m) : [doc.source];
            const headings: string[] = [];
            for (const group of groups) {
                const title = /^(#{1,6})\s+(.+)/.exec(group);
                if (title) {
                    headings.length = title[1].length;
                    headings[title[1].length - 1] = title[2];
                    heading = headings.filter(Boolean).join(' / ');
                }
                const chars = [...group.trim()];
                for (let offset = 0; offset < chars.length; offset += doc.split.size - doc.split.overlap) {
                    if (chunks.length >= 10000) {
                        doc.status = 'failed';
                        doc.updated = Date.now();
                        Object.assign(doc.attempts[doc.attempts.length - 1], {
                            status: 'failed',
                            reason: '切分结果超过 10,000 个 Chunk，请调整切分参数后重新上传。',
                        });
                        return;
                    }
                    chunks.push({ text: chars.slice(offset, offset + doc.split.size).join(''), heading });
                    if (offset + doc.split.size >= chars.length) break;
                }
            }
            doc.chunks = chunks;
            doc.status = 'ready';
            doc.updated = Date.now();
            doc.attempts[doc.attempts.length - 1].status = 'ready';
        },
        Math.max(0, (doc.completeAt || 0) - Date.now()),
    );
}

export const api = {
    get libraries() {
        return data.libraries.filter((item) => !item.deleted).sort((a, b) => b.updated - a.updated);
    },
    get documents() {
        return data.documents;
    },
    get conversations() {
        return data.conversations;
    },
    get memories() {
        return data.memories;
    },
    get memoryEnabled() {
        return data.memoryEnabled;
    },
    set memoryEnabled(value: boolean) {
        data.memoryEnabled = value;
    },
    async login(user: string, password: string) {
        if (!user.trim() || password.length < 6) throw Error('请输入账号及至少 6 位密码。');
        await wait(300);
        const name = user.trim().toLowerCase();
        account.value = '';
        load(name);
        account.value = name;
        sessionStorage.setItem(prefix + 'session', name);
    },
    logout() {
        account.value = '';
        sessionStorage.removeItem(prefix + 'session');
        Object.assign(data, initial());
    },
    saveLibrary(value: Pick<Library, 'name' | 'description'>, existing?: Library) {
        const name = value.name.trim();
        if (!name) throw Error('请输入知识库名称。');
        if (this.libraries.some((item) => item !== existing && item.name === name)) throw Error('知识库名称已存在。');
        const fields = { name, description: value.description.trim(), updated: Date.now() };
        if (existing) Object.assign(existing, fields);
        else data.libraries.push({ id: id(), ...fields });
    },
    deleteLibrary(library: Library) {
        library.deleted = true;
    },
    async upload(library: string, file: File, split: Split) {
        const owner = account.value;
        if (!/\.(md|markdown|txt)$/i.test(file.name) || file.size > 2 * 1024 * 1024 || !file.size) {
            throw Error('请选择不超过 2 MiB 的非空 Markdown 或 TXT 文件。');
        }
        if (
            !Number.isInteger(split.size) ||
            !Number.isInteger(split.overlap) ||
            split.size < 1 ||
            split.size > 4000 ||
            split.overlap < 0 ||
            split.overlap >= split.size
        ) {
            throw Error('目标长度须为 1–4000 的整数，重叠须为非负整数且小于目标长度。');
        }
        let source: string;
        try {
            source = new TextDecoder('utf-8', { fatal: true }).decode(await file.arrayBuffer());
        } catch {
            throw Error('无法读取文件，请选择 UTF-8 编码的文件。');
        }
        if (!source.trim() || source.includes('\0')) throw Error('文件必须包含有效文本。');
        if (account.value !== owner || !this.libraries.some((item) => item.id === library))
            throw Error('知识库已不可用。');
        if (data.documents.some((doc) => doc.library === library && doc.source === source))
            throw Error('知识库中已存在相同内容。');
        const docId = id();
        data.documents.push({
            id: docId,
            library,
            source,
            name: file.name,
            split: { ...split },
            status: 'pending',
            chunks: [],
            updated: Date.now(),
            attempts: [],
        });
        this.retryDocument(data.documents.find((doc) => doc.id === docId)!);
    },
    retryDocument(doc: Doc) {
        doc.status = 'pending';
        doc.updated = Date.now();
        doc.completeAt = doc.updated + 1150;
        doc.attempts.push({ date: doc.updated, status: 'pending' });
        const library = this.libraries.find((item) => item.id === doc.library);
        if (library) library.updated = doc.updated;
        schedule(doc);
    },
    deleteDocument(doc: Doc) {
        data.documents.splice(data.documents.indexOf(doc), 1);
        const library = this.libraries.find((item) => item.id === doc.library);
        if (library) library.updated = Date.now();
    },
    saveMemory(value: Pick<Memory, 'text' | 'keywords' | 'type' | 'enabled'>, existing?: Memory) {
        const text = value.text.trim();
        const keywords = !isGlobal(value.type) ? value.keywords.trim().replace(/，/g, ',') : '';
        if (
            !text ||
            [...text].length > 500 ||
            [...keywords].length > 200 ||
            text.includes('\0') ||
            keywords.includes('\0')
        ) {
            throw Error('正文需为 1–500 个字符，关键词最多 200 字符，且不能包含空字符。');
        }
        if (data.memories.some((item) => item !== existing && item.type === value.type && item.text === text))
            throw Error('相同记忆已存在。');
        if (!existing && data.memories.length >= 50) throw Error('已达到 50 条记忆上限。');
        const fields = { ...value, text, keywords, updated: Date.now() };
        if (existing) Object.assign(existing, fields);
        else data.memories.push({ id: id(), ...fields });
    },
    deleteMemory(memory: Memory) {
        data.memories.splice(data.memories.indexOf(memory), 1);
    },
    createChat(library: string) {
        const chatId = id();
        data.conversations.push({ id: chatId, library, title: '新会话', turns: [], updated: Date.now() });
        return chatId;
    },
    renameChat(chat: Conversation, title: string) {
        if (!title.trim()) throw Error('请输入会话名称。');
        chat.title = title.trim();
        chat.updated = Date.now();
    },
    deleteChat(chat: Conversation) {
        data.conversations.splice(data.conversations.indexOf(chat), 1);
    },
    search(library: string, question: string, topK: number, strategy: Strategy) {
        if (!this.libraries.some((item) => item.id === library)) return [];
        const terms = question.toLowerCase().match(/[a-z0-9_]+|[\u3400-\u9fff]/g) || [];
        const matches = data.documents
            .filter((doc) => doc.library === library && doc.status === 'ready')
            .flatMap((doc) =>
                doc.chunks.map((chunk, index) => {
                    const corpus = (doc.name + chunk.heading + chunk.text).toLowerCase();
                    const count = terms.filter((term) => corpus.includes(term)).length;
                    return { ...chunk, docId: doc.id, document: doc.name, index, count };
                }),
            );
        // 用文本匹配模拟两路召回。RRF 只累加排名贡献，重排使用单独的演示分数。
        const vector = [...matches]
            .sort((a, b) => b.count - a.count)
            .map((match, index) => ({ ...match, score: 1 / (1 + index / 8) }));
        const bm25 = matches
            .filter((match) => match.count > 0)
            .map((match) => ({ ...match, score: (match.count * 3) / Math.sqrt(Math.max(1, match.text.length)) }))
            .sort((a, b) => b.score - a.score);
        if (strategy === 'VECTOR') return vector.slice(0, topK);
        if (strategy === 'BM25') return bm25.slice(0, topK);
        const key = (match: (typeof matches)[number]) => match.docId + ':' + match.index;
        const keywordRanks = new Map(bm25.map((match, rank) => [key(match), rank + 1]));
        const fused = vector
            .map((match, rank) => {
                const keywordRank = keywordRanks.get(key(match));
                return { ...match, score: 1 / (61 + rank) + (keywordRank ? 1 / (60 + keywordRank) : 0) };
            })
            .sort((a, b) => b.score - a.score);
        if (strategy === 'HYBRID_RRF') return fused.slice(0, topK);
        return fused
            .slice(0, Math.max(20, topK))
            .map((match) => ({
                ...match,
                score: ((match.count + 1) / (terms.length + 1)) * 0.8 + Math.min(match.text.length, 300) / 1500,
            }))
            .sort((a, b) => b.score - a.score)
            .slice(0, topK);
    },
    async ask(chat: Conversation, question: string, strategy: Strategy, simulateFailure: boolean) {
        if (chat.turns.some((turn) => turn.status === 'pending')) return;
        if (!chat.turns.length && chat.title === '新会话') chat.title = [...question].slice(0, 26).join('');
        chat.updated = Date.now();
        const turnId = id();
        chat.turns.push({ id: turnId, question, strategy, status: 'pending' });
        const turn = chat.turns[chat.turns.length - 1];
        await wait(1500);
        if (turn.status !== 'pending' || !data.conversations.includes(chat)) return;
        if (simulateFailure) {
            turn.status = 'failed';
            return;
        }
        const evidence = this.search(chat.library, question, 3, strategy);
        const trace =
            data.memoryEnabled && evidence.length
                ? data.memories
                      .filter(
                          (memory) =>
                              memory.enabled &&
                              (isGlobal(memory.type) ||
                                  memory.keywords
                                      .split(',')
                                      .some(
                                          (word) =>
                                              word.trim() && question.toLowerCase().includes(word.trim().toLowerCase()),
                                      )),
                      )
                      .sort((a, b) => Number(isGlobal(a.type)) - Number(isGlobal(b.type)) || b.updated - a.updated)
                      .slice(0, 10)
                      .map(({ id, type }) => ({ id, type }))
                : [];
        Object.assign(turn, {
            status: 'ready',
            evidence,
            trace,
            answer: evidence.length
                ? seed.mockAnswer(evidence[0].text, /自动配置/.test(evidence[0].heading))
                : '当前知识库没有检索到可用证据。请尝试调整问题或导入相关文档。',
        });
        chat.updated = Date.now();
    },
    cancel(turn: Turn) {
        if (turn.status === 'pending') turn.status = 'cancelled';
    },
};
