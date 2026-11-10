# M1-04 解析验证

## 架构与坐标协议

DocumentParser 是独立纯 Java 组件，输入文件名与 UTF-8 字节，无 Spring、存储或数据库依赖。ParsedDocument 保存完整原文；TXT 一个连续片段，Markdown 按原始行输出连续片段（不是 Chunk）。Fragment 带不可变 Heading 列表，Heading 保存展示文字、层级和文字源码范围。SourceSpan 校验非负、非空及提取时上界，ParsedDocument 校验片段连续覆盖全文和标题文字精确回取；不允许拼接不连续映射。

所有范围为严格 UTF-8 解码后原文 Unicode 码点 [start,end)。BOM 占一位，CRLF 占两位，Emoji/补充平面字符占一位。码点扫描确定行边界；正则组的 UTF-16 下标经 codePointCount 转换后才参与标题范围。正文完全保留，不 trim、不归一化、不改换行。

错误分类：INVALID_UTF8、UNSUPPORTED_FORMAT、NO_CONTENT、UNRELIABLE_MAPPING。空输入、仅 BOM/空白（含 Unicode 空格）失败；不记录正文。Markdown 语法标记本身保留为原文内容，不声称其等同于渲染后的可见内容。

## Markdown 范围与限制

识别顶层最多三空格缩进的 1–6 级 ATX 标题，清除标题尾部闭合 # 和尾部空白用于展示，仍保留连续文字范围。跳级仅追加实际标题，不生成虚构父级；同级或更高级移除旧路径后追加。空标题清除对应旧路径而不新增文字节点。

支持反引号和波浪号围栏，匹配字符及最小长度关闭，未闭合围栏延续至 EOF。围栏内不识别标题。段落、多行段落、列表、引用、行内格式均原样保留，不生成语义 AST。Setext、HTML、表格、嵌套列表/引用中的标题或围栏、转义与链接不作语义解释；复杂容器不保证 CommonMark 语义，源码位置仍精确。不是完整 CommonMark 解析器。

## M1-05 衔接

OriginalFileStore.read 返回的 byte[] 可直接交给 parse，不增加上传同步调用或适配流程。M1-05 必须读取所属 IndexVersion.chunkingConfig 持久化快照（version=1，FIXED_CODE_POINT，800/120），不能读取 application.yml 代替。后续按原文码点窗口生成连续范围，以窗口起点所在 Fragment 的路径作为确定性标题策略候选；跨标题窗口策略由 M1-05 最终确定。通过 SourceSpan.extract(source) 与最终 Chunk.content 相等及边界断言证明 sourceStart/sourceEnd；如跳过标记或空白，不能把不连续文本伪装成单一范围。本次未实现窗口、重叠、ID、入库或发布。

## 离线测试与执行

DocumentParserTests 15 项：12 组参数分别对 MD/TXT 检查原文回取和确定性，覆盖英文数字、中文、Emoji/补充平面、BOM、LF/CRLF/混合换行、末尾换行、行内语法、列表引用、重复正文；综合标题测试覆盖 BOM+中文+Emoji+CRLF+多级标题+围栏伪标题、重复标题位置及跳级/重置。另验证空白/空输入、非法 UTF-8、不支持后缀、越界/非连续范围拒绝。

综合回归样本固定在 headingsAndFences 测试中，可供 M1-05 复用；首标题文字从码点 3 开始（BOM、#、空格之后）。每个成功结果经构造器检查范围上界及标题精确回取，并在测试中将全部片段回取拼成完整原文。

已执行 mvn spotless:apply、mvn spotless:check、mvn -DskipTests test-compile，均成功；局部测试执行 DocumentParserTests、ImportTests、ImportApiTests。未启动基础设施、未调用 MinIO/ES/模型，未执行数据库或全量测试：本次只有纯解析新增，既有上传链路未改。现有编译弃用提示及 Mockito agent 警告不属于解析失败。

已知冻结架构 Boot 3.x 与 pom 4.1.2 差异保持不变；无依赖、迁移、表名或 API 变更。

实际局部测试总计 20 项通过（解析 15、导入单元 4、导入 API 1），0 失败、0 错误、0 跳过。已审阅完整新增源码与暂存 diff，git diff --check 和 git diff --cached --check 通过。

## 架构评审修正（基线 7ae8cf1）

DocumentParser 现为接口（extension、parse(byte[])）；TxtDocumentParser 与 MarkdownDocumentParser 各自只处理单一格式。DocumentParserFactory 显式创建两种实现，以大小写无关的扩展名选择；未知类型抛 UNSUPPORTED_FORMAT，构造时拒绝重复扩展名和超出 md/txt 的实现，避免顺序决定匹配结果。没有扫描或插件注册机制。ParserSource 包内共享严格 UTF-8 解码、空内容检查及码点边界构建。原有原文、错误分类和位置协议不变。

ParsedDocument 校验建立一次码点到 UTF-16 边界数组，O(N) 时间与空间；片段覆盖检查只比较边界，标题用边界表回取，并通过局部 Set 对相同 Heading 去重校验。不存在逐片段从头扫描全文。总体成本为原文长度、片段路径条目及不同标题文字校验长度之和。SourceSpan.extract 作为独立单次提取 API 仍为 O(N)，不用于批量构造校验；调用方批量回取应顺序扫描，避免自行引入二次成本。边界表与 Set 仅在构造校验期间存在，无持久缓存或依赖。

职责边界：Parser 负责格式解析及来源映射；未来 Splitter 读取所属 IndexVersion 持久化 ChunkingConfig 执行确定性切分（FIXED_CODE_POINT v1 / 800 / 120 不变）；未来 ProcessingService 负责选择、编排和调用。当前仅提供 ParserFactory，不创建 Splitter 或 ProcessingService 空壳，也不改变原文、上传或 Chunk 持久化流程。

保留原 15 项解析测试，新增路由/冲突检测及大量短行测试，解析共 17 项。短行输入 50,001 行、250,010 码点，含 BOM、CRLF、中文、Emoji、补充平面字符及一个标题；逐片段顺序验证连续范围和源码回取。当前机器单次实测 parseAndValidationMs=291.6077（含 UTF-8 编码、解析和构造校验，不含随后逐片段断言）。这是单次观测，非稳定基准、性能门槛或跨机器承诺；未作旧实现对照测量。

本次执行 mvn spotless:apply、mvn spotless:check、mvn -DskipTests test-compile，以及 DocumentParserTests、ImportTests、ImportApiTests 局部测试。未执行全量/数据库/MinIO/ES/在线模型验证，本次未改这些链路。原有 Markdown 支持范围及限制保持不变。

修正后局部测试总计 22 项通过（解析 17、导入单元 4、API 1），0 失败、0 错误、0 跳过。完整修改已审阅，git diff --check 与暂存检查通过。

## commonmark 生产适配（基线 d92943e）

MarkdownDocumentParser 使用 commonmark-java 0.32.0 的公开 Parser/Node/Heading/SourceSpans API，移除标题正则和围栏状态机。严格解码、接口、Factory、TXT、ParsedDocument、SourceSpan 与切分协议均不变。依赖候选改为编译范围进行源码验证；仅在下述测试通过后提交为正式依赖。

原文完整保留；仅 AST 解析视图跳过开头一个 BOM，所有库 UTF-16 inputIndex 加回 BOM 宽度，再使用一次构建的边界表转换至原文码点。代理对内部边界和越界拒绝 UNRELIABLE_MAPPING。正文 Fragment 保持逐原始行连续覆盖，包括 BOM、CRLF、空白和所有语法标记，不使用 AST literal 或渲染文本作为正文。

标题文字取首末行内子节点的源码包络；普通 ATX/Setext 标题去掉外围标题语法，内部强调、链接、转义保留原样。多行或容器内标题包络包含中间换行、容器前缀与标记，展示文字严格等于该连续范围回取。这样不拼接多个离散 SourceSpans，不声称展示为纯渲染文本。空标题更新层级但不新增空 Heading。

标题路径作用域按 AST 父容器隔离：文档标题影响之后同级正文；进入引用块、列表及每个列表项时继承当时外层路径，但内部标题只更新本容器后续兄弟内容；退出后恢复外层，兄弟列表项不继承前项标题。内部同级/高级标题可替换继承路径，但不会修改外层。空白行按所在容器可用源码范围及外层路径保留；不作为语义 Chunk 边界。

新行为：识别 Setext、列表/引用块内标题，代码块判断服从 CommonMark AST；旧手写识别中的不符合 CommonMark 行为不再维持。GFM 表格仍无扩展支持。正文映射不受语义限制影响。多行标题可能显示原始容器前缀，这是单一连续 SourceSpan 协议下明确选择的原文语义，不是清理后的展示标题。

新增容器作用域和多行 Setext 连续包络测试；原有 17 项保留。实验测试中对“旧实现不识别三个容器/Setext 标题”的断言更新为新实现识别三个；实验中 raw commonmark 的 BOM 差异断言保留。大规模基准从 CommonMarkFeasibilityTests 移除，新建 MarkdownPerformanceIT，仅显式 -Dtest=MarkdownPerformanceIT 执行；默认 *Tests 路径不运行 2 MiB 实验。原 50,001 短行正确性回归保留。

性能测量以相同 2 MiB 固定输入，预热 3 次、测量 7 次，使用 -Xmx512m 独立测试 JVM；记录完整生产解析（含严格解码、AST、坐标适配、ParsedDocument 构造）的 min/median/max 和 JVM heap used/max。heap used 含未回收临时对象，不是对象保留大小或峰值；不与历史不同条件数据推导速度结论。未做 profiler，极深恶意容器的递归栈资源界限尚未压测。

实际验证：mvn spotless:apply、mvn spotless:check、mvn -DskipTests test-compile 成功；mvn "-Dtest=DocumentParserTests,CommonMarkFeasibilityTests,ImportTests,ImportApiTests" test 共 26 项通过（19+2+4+1），0 失败/错误/跳过。首次候选验证因并发编辑测试文件触发格式检查失败，随后重新格式化并完整执行上述命令通过，无绕过检查。

显式 mvn "-Dtest=MarkdownPerformanceIT" "-DargLine=-Xmx512m" test：1 项通过。输入 2,097,220 bytes，min/median/max=593.2754/751.0073/890.7766 ms，测量末尾 heap used=394,571,024 bytes，heap max=536,870,912 bytes。Java 21.0.10 / Windows 11。通过位置回归后确认 commonmark 依赖正式采用 compile 范围。未执行全量、数据库、MinIO、ES、模型服务；无相关链路变更。git diff --check 和暂存检查通过，完整变更已审阅。未进入 M1-05。
