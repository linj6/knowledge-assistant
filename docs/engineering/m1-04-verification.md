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
