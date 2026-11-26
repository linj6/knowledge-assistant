import { defaults, type Doc, type Library, type Memory, type Conversation } from './model';

const today = new Date(2026, 10, 25, 15).getTime();
export const libraries: Library[] = [
    {
        id: 'spring',
        name: 'Spring Boot 学习笔记',
        description: '从自动配置到事务管理，整理日常开发中的 Spring 实践。',
        updated: today,
    },
    {
        id: 'rag',
        name: 'RAG 工程实践',
        description: '涵盖文档切分、混合检索、重排与引用验证。',
        updated: today - 86400000,
    },
    {
        id: 'database',
        name: '数据库与缓存',
        description: '记录 PostgreSQL、索引设计与缓存一致性。',
        updated: today - 172800000,
    },
];
const sources = [
    [
        'spring',
        'Spring Boot 自动配置.md',
        '自动配置',
        '自动配置根据类路径、配置属性以及容器中的 Bean 决定是否启用。可以使用条件报告查看匹配结果，通过自定义 Bean 覆盖默认配置。',
    ],
    [
        'spring',
        'Spring 事务管理.md',
        '事务代理',
        '声明式事务通常通过代理拦截方法调用。同一个对象内部直接调用方法可能绕过代理，需要通过代理入口调用。',
    ],
    [
        'spring',
        'Spring 配置属性.md',
        '配置绑定',
        '使用 @ConfigurationProperties 将层级配置绑定到类型安全的对象，并通过校验注解检查启动配置。',
    ],
    ['rag', '混合检索与 RRF.md', '排名融合', 'RRF 按候选在各路结果中的排名累加 1/(k+rank)，融合向量检索与 BM25 候选。'],
    ['rag', 'Chunk 切分实践.md', '标题路径', 'Markdown 结构感知切分保留标题路径。固定长度切分可以使用重叠保留上下文。'],
    [
        'database',
        'PostgreSQL 索引笔记.txt',
        '执行计划',
        '使用 EXPLAIN 查看查询计划。B-tree 索引适用于等值与范围查询，维护索引会增加写入成本。',
    ],
    ['spring', '批处理导入笔记.md', '导入重试', '导入失败后可以重新提交任务。'],
    [
        'spring',
        'Spring Boot 应用测试.md',
        '测试切片',
        'WebMvcTest 聚焦 MVC 组件，适合验证请求映射、校验与序列化。SpringBootTest 可以加载完整应用上下文。',
    ],
    [
        'spring',
        '应用运行指标.md',
        'Actuator',
        'Actuator 可暴露健康检查和指标端点。生产环境应按需开放端点，并限制访问权限。',
    ],
] as const;
export const documents: Doc[] = sources.map(([library, name, heading, text], index) => {
    const status = index === 6 ? 'failed' : 'ready';
    return {
        id: 'doc-' + index,
        library,
        name,
        source: '# ' + heading + '\n\n' + text,
        split: { ...defaults, mode: name.endsWith('.md') ? 'MARKDOWN' : 'FIXED' },
        status,
        chunks: status === 'ready' ? [{ text, heading }] : [],
        updated: today,
        attempts: [{ date: today, status, reason: status === 'failed' ? '模拟索引发布超时' : undefined }],
    };
});
export const memories: Memory[] = [
    {
        id: 'memory-1',
        type: 'ANSWER_PREFERENCE',
        text: '优先用中文说明，并给出简短示例。',
        keywords: '',
        enabled: true,
        updated: today,
    },
    {
        id: 'memory-2',
        type: 'TECH_PREFERENCE',
        text: '讨论 Spring 时，我更关注 Java 21 下的实现。',
        keywords: 'Spring, Java',
        enabled: true,
        updated: today,
    },
];
export const conversations: Conversation[] = [
    {
        id: 'conversation-1',
        library: 'spring',
        title: 'Spring Boot 自动配置原理',
        updated: today,
        turns: [
            {
                id: 'turn-1',
                question: 'Spring Boot 自动配置是如何工作的？',
                strategy: 'HYBRID_RRF',
                status: 'ready',
                answer: 'Spring Boot 通过**条件判断**决定是否加载默认配置。常见条件包括类路径上的类型、配置属性，以及容器中是否已存在对应 Bean。',
                evidence: [
                    {
                        ...documents[0].chunks[0],
                        document: documents[0].name,
                        docId: documents[0].id,
                        index: 0,
                        score: 0.0328,
                    },
                ],
                trace: [],
            },
        ],
    },
    { id: 'conversation-2', library: 'rag', title: '理解混合检索', updated: today - 86400000, turns: [] },
];
export const suggestions = ['概括当前知识库的主要内容', '有哪些实践适合入门学习？', '给我一个具体的配置示例'];
export const mockAnswer = (text: string, showExample: boolean) =>
    [
        '根据知识库中的资料，可以先抓住以下要点。',
        '> ' + text.slice(0, 550).replace(/\n/g, '\n> '),
        '| 阅读步骤 | 建议 |\n| --- | --- |\n| 理解原理 | 先阅读引用中的概念与适用条件 |\n| 检查实践 | 用最小示例验证配置与行为 |',
        '例如，可以在开发环境开启调试输出，检查应用启动时的条件报告。\n\n```yaml\ndebug: true\n```',
    ]
        .filter((_, index) => index < 3 || showExample)
        .join('\n\n');
