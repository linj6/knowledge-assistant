# M1-04-COMMONMARK-SPIKE

## 结论

建议后续引入 commonmark-java 负责结构识别，但必须先完成 BOM 视图、Unicode 边界转换和标题展示范围适配的验收；本次不批准直接替换。没有发现保留原始连续片段方案的不可解决阻断。渲染文本直接作为正文并只保存一个范围的方案不满足冻结协议。

生产源码未修改；只新增测试类和 org.commonmark:commonmark:0.32.0 的 test scope 依赖。基线 a8f28d5，开始时工作区干净。

## 官方依据与依赖

- [官方仓库](https://github.com/commonmark/commonmark-java)。
- [0.32.0 SourceSpan API 源码](https://github.com/commonmark/commonmark-java/blob/commonmark-parent-0.32.0/commonmark/src/main/java/org/commonmark/node/SourceSpan.java)：column、length 使用 Java UTF-16 单元，多行 block 每行一个 span。
- [0.32.0 DocumentParser 源码](https://github.com/commonmark/commonmark-java/blob/commonmark-parent-0.32.0/commonmark/src/main/java/org/commonmark/internal/DocumentParser.java)：parse(String) 按原始换行宽度推进 inputIndex。
- [核心 POM](https://github.com/commonmark/commonmark-java/blob/commonmark-parent-0.32.0/commonmark/pom.xml) 与 [许可证](https://github.com/commonmark/commonmark-java/blob/commonmark-parent-0.32.0/LICENSE.txt)：BSD-2-Clause，分发须保留版权、条件和免责声明。
- Maven Central 版本 POM HTTP 200；本机 Maven 实际通过配置的 Aliyun central 镜像解析成功。dependency:tree 仅有核心 test JAR，无传递运行时依赖。核心 JAR 221,729 字节，sources JAR 148,255 字节；javap major version 55（Java 11 字节码），Java 21.0.10 实际编译运行验证成功。

## 坐标与语义证据

测试直接启用 BLOCKS_AND_INLINES，并检查所有节点 getSourceSpans 的 getInputIndex/getLength/getLineIndex/getColumnIndex。原始字符串建立 UTF-16→码点边界表，代理对内部标记非法；每个范围检查原始行起点+column=inputIndex、起终点合法、转换后准确回取。不使用全文 indexOf。

综合样本逐字复用 DocumentParserTests 的 BOM、中文、Emoji、CRLF、多级/重复/跳级标题和伪标题围栏输入。真实输出：首 Paragraph span=(line=0,column=0,input=0,length=7)，第二行 Heading=(1,0,9,6)，围栏三行 spans=(4,0,31,7)/(5,0,40,5)/(6,0,47,3)。commonmark 标题 5 个；当前实现唯一标题位置 6 个。初次实验误将两者数量写为 6/7，断言失败后按样本核对修正为 5/6，未修改生产代码。

最小 Unicode 输入 `😀x\r\n# 中文𠀀\n重复\r重复`：首 Paragraph=(0,0,0,3)，Heading=(1,0,5,6)，后续 Paragraph spans=(2,0,12,2)/(3,0,15,2)。Heading UTF-16 [5,11) 对应码点 [4,9)，回取为 `# 中文𠀀`。CRLF 两码点保持；Emoji 和补充汉字各一码点。

最小多区间输入 `> 😀甲\r\n> 乙𠀀`：子 Paragraph spans=(0,2,2,3)/(1,2,9,3)。区间间包含 CRLF 和下一行引用前缀，不能省略后伪造连续来源。Heading span 包含 #、Setext 下划线等语法，不等于展示标题文字范围。Setext 标题有两个 span。强调、链接、转义的 AST/渲染文本与原文不等长；NUL 可被库替换为替代字符，但源码切片仍来自原文，不能使用 literal 冒充原文。

| 分类 | 对照发现 | 处理 |
|---|---|---|
| A | Setext、列表内标题、引用内标题当前不识别；成熟库正确识别 | 若扩展语义范围，使用 AST |
| B | 普通多行段落、列表/引用、缩进代码当前原样保留，库提供对应节点 | 当前原文引用不受影响 |
| B | 未闭合围栏延续 EOF、围栏内伪标题不作为 Heading | 两者一致 |
| B | 核心库无 GFM 表格节点，表格输入为 Paragraph | 本实验不引入扩展 |
| C | BOM 使首行标题不识别 | 仅解析视图去首 BOM，所有库偏移加 1 UTF-16 后转原文码点；原文不变，此方案尚未实现验收 |
| C | UTF-16、多行 span 不含换行、容器子节点区间不连续 | 显式转换边界并保留区间；连续正文继续用原文片段 |
| C | Heading 范围与渲染标题不相等 | 展示保留原始行内标记，或另行建立细粒度映射 |
| D | 未发现连续原文方案阻断 | 不代表已完成生产适配 |

边界覆盖空输入、空行、混合 CR/LF/CRLF、NUL、空标题、破损链接、未闭合围栏、行内强调/链接/转义、重复正文；原解析器 17 项回归另行执行。测试不声称两个解析器 AST 等价，也不以测试数量评价语义优劣。

## 性能方法

同一 JVM、固定非随机输入，预先构造字符串与 UTF-8 bytes；每模式预热 3 次、测量 7 次，轮转执行顺序，volatile sink 保留结果。模式 0 当前解析器（含解码及 ParsedDocument 校验）；1 AST 无 spans；2 AST 开 spans；3 AST+spans+Unicode 边界转换与全部 span 校验。模式 3 未生成 ParsedDocument 或标题展示路径，不能称生产完整适配耗时。无外部服务、无强制 GC；不是 JMH、无独立 fork，JIT/GC/执行顺序仍有噪声。不得与历史 291.6077 ms 单次观测比较。

输入依次为仓库真实验证文档 6,558 bytes、固定 Markdown 块重复 400 次 42,800 bytes、同块重复至 2,097,200 bytes、大量短行 650,008 bytes、30 层引用及三级列表重复 300 次 29,400 bytes。标题使用 Markdown 合法最大六级。Java 21.0.10 / Windows 11。

首次测量中位数 ms（初次运行仅语义数量断言失败，性能部分完整运行；最终复跑原始数据见下文）：

| 输入 | 当前 | AST | AST+spans | AST+spans+坐标校验 |
|---|---:|---:|---:|---:|
| 小文档 | 7.055 | 3.017 | 2.470 | 4.075 |
| 中等 | 14.408 | 16.845 | 19.170 | 29.134 |
| 约 2 MiB | 201.663 | 329.943 | 356.093 | 563.753 |
| 短行 | 30.150 | 25.330 | 26.180 | 48.876 |
| 嵌套 | 2.364 | 5.256 | 8.085 | 6.954 |

首次每组结束全 JVM used heap 为 34,112,416 / 124,629,192 / 942,669,824 / 1,459,617,792 / 1,684,013,056 bytes；含未回收临时对象和全部模式，不能据此归因单一实现、推断 retained size 或内存泄漏。未做分配 profiler。较大 AST 成本可见但波动大，仍需生产适配后在目标内存限制下做独立基准；不能从局部中位数宣称成熟库更快。

## 后续最小方案（未实施）

保留 DocumentParser 接口、Factory、TXT、ParsedDocument、SourceSpan 和严格解码。仅替换 MarkdownDocumentParser 的标题/围栏正则与手工状态机，使用 AST 结构；删除这两类手工判断。保留原文顺序扫描、换行及空白覆盖、Unicode 边界校验。新增最小包内 SourceSpan 适配，处理 BOM 视图、UTF-16→码点及多区间，明确容器标题 headingPath 作用域，标题展示不得依赖渲染后文字的单一范围。依赖改为运行时须另行授权。

Splitter 未来仍读取所属 IndexVersion 持久化 ChunkingConfig：FIXED_CODE_POINT v1 / 800 / 120；ProcessingService 负责选择编排。AST 只提供结构和位置，不把其多区间渲染文本交给固定窗口。当前未创建上述组件、Chunk、Worker 或索引。

## 最终复跑记录

```text
BENCH sample=0 bytes=6558 mode=0 medianMs=1.9946 minMs=1.7395 maxMs=7.143
BENCH sample=0 bytes=6558 mode=1 medianMs=1.2472 minMs=1.1227 maxMs=2.4058
BENCH sample=0 bytes=6558 mode=2 medianMs=1.9964 minMs=0.9542 maxMs=2.4736
BENCH sample=0 bytes=6558 mode=3 medianMs=2.0275 minMs=1.6934 maxMs=5.305
HEAP sample=0 usedBytes=34193720
BENCH sample=1 bytes=42800 mode=0 medianMs=20.2631 minMs=15.6673 maxMs=35.1186
BENCH sample=1 bytes=42800 mode=1 medianMs=28.5965 minMs=11.3861 maxMs=55.0155
BENCH sample=1 bytes=42800 mode=2 medianMs=35.0336 minMs=21.4285 maxMs=55.0242
BENCH sample=1 bytes=42800 mode=3 medianMs=41.3705 minMs=30.4522 maxMs=68.5951
HEAP sample=1 usedBytes=124564056
BENCH sample=2 bytes=2097200 mode=0 medianMs=171.6793 minMs=99.9475 maxMs=253.4899
BENCH sample=2 bytes=2097200 mode=1 medianMs=218.0483 minMs=160.2 maxMs=298.2548
BENCH sample=2 bytes=2097200 mode=2 medianMs=346.6445 minMs=222.9603 maxMs=521.4248
BENCH sample=2 bytes=2097200 mode=3 medianMs=389.6284 minMs=302.7178 maxMs=563.0097
HEAP sample=2 usedBytes=1613758464
BENCH sample=3 bytes=650008 mode=0 medianMs=29.9016 minMs=23.258 maxMs=61.2208
BENCH sample=3 bytes=650008 mode=1 medianMs=27.5339 minMs=21.839 maxMs=62.8027
BENCH sample=3 bytes=650008 mode=2 medianMs=36.7062 minMs=29.2126 maxMs=101.7261
BENCH sample=3 bytes=650008 mode=3 medianMs=56.3836 minMs=48.8988 maxMs=75.4349
HEAP sample=3 usedBytes=1052770304
BENCH sample=4 bytes=29400 mode=0 medianMs=4.5749 minMs=2.472 maxMs=9.1933
BENCH sample=4 bytes=29400 mode=1 medianMs=15.2922 minMs=8.2012 maxMs=27.8996
BENCH sample=4 bytes=29400 mode=2 medianMs=15.1731 minMs=9.4541 maxMs=24.6897
BENCH sample=4 bytes=29400 mode=3 medianMs=19.4964 minMs=11.6916 maxMs=25.4214
HEAP sample=4 usedBytes=1277165568
JVM 21.0.10 Windows 11
```

实际执行 spotless:apply、spotless:check、-DskipTests test-compile 均成功；实验 3 项和原解析回归 17 项，共 20 项通过，0 失败、0 错误、0 跳过。git diff --check 及暂存检查通过。未运行全量测试、基础设施或在线服务。生产源码与正式协议未变；完整 diff 已审阅。架构文档旧 Boot 版本差异保持原样。
