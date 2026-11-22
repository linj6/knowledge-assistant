# WEB-03-03 综合验收（2026-11-12）

## 基线与结论

分支 feature/web-ui-v1；HEAD 868e7e7b22d1b6998914c7b2db35fda7e1b81fef。开始时只有 target-web01-check.log、target-web01-final.log 两个未跟踪文件，本轮未读取、删除、覆盖或提交它们。未提交代码，Commit SHA 无新增。

自动化通过；静态审查完成；真实浏览器未完成；真实 Worker 未执行。READY TO COMMIT：否（严格验收证据仍缺）；READY TO CLOSE：否。未发现剩余自动化功能阻断，视觉、键盘和浏览器验收是关闭前的验收阻断。

## 实际执行的命令与结果

- `node --experimental-vm-modules --test src/test/js/*.test.mjs`：修复前65/65，修复后66/66；失败、跳过均0。VM Modules 实验性提示不影响结果。
- `mvn "-Dmaven.repo.local=D:\develop\repository" "-Dtest=WebStaticResourcesTests,KnowledgeBaseApiTests,DocumentQueryTests,ImportApiTests,ImportTests,ImportTaskTests,TaskExecutionTests,TaskSchedulerRecoveryTests" test`：25项，失败/错误/跳过均0，BUILD SUCCESS。包含 Spotless validate 检查，227个Java文件及pom检查通过。Mockito动态代理警告不影响结果。
- `Get-ChildItem src/main/resources/static/js/*.js | ForEach-Object { Get-Content -Raw $_.FullName | node --input-type=module --check; if ($LASTEXITCODE -ne 0) { throw 'syntax failed' } }`：全部模块语法通过。
- `git diff --check`：通过；仅有Git行尾转换提示。
- `git status --short`、`git branch --show-current`、`git rev-parse HEAD`、`git diff --stat`：已执行。最终改动为knowledge.js、web-import.test.mjs及本记录；两个既有日志仍未跟踪。

未执行全量Maven、真实供应商API、Docker启动、开发数据库写入、索引重建。定向测试使用模拟依赖；WebStaticResourcesTests启动隔离随机端口Spring应用，真实HTTP校验后自动关闭，不是浏览器测试。

## 只读审查与缺陷

审阅index.html、app.css、app.js、api.js、state.js、knowledge.js、import.js、两个JS测试及harness、WebStaticResourcesTests和前两轮记录。

DOM按导航、共享选择器及三个section组织，知识库动态表单为行内表单。AppState是选择来源；创建后刷新列表，用户后续选择优先。文档使用后端page/pageSize/total，ID和total保留字符串精度。业务名称、描述、文件名等使用textContent，不插入HTML。FormData不手设Content-Type。导入轮询active/timer/epoch限制单链，切库、隐藏、导航暂停旧查询；上传中导航允许上传请求完成，无重发，后台不会继续轮询。文档和创建的一次性请求可能在导航后完成，均有超时清理；不应声称所有页面切换都会取消所有请求。

实际缺陷：文档GET等待20秒，AbortError被静默忽略，仍显示加载中。复现：选择知识库后阻塞文档响应直到超时。修复：当前代次取消提示“文档加载等待超时，请刷新文档重试”；旧代次切库取消仍静默。新增行为测试触发20秒定时器、拒绝请求、点击刷新并返回空列表，验证恢复成功。现有旧请求测试继续通过。

## 体验、响应式与可访问性静态审查

暖白背景#FAFAF8、白卡片、浅灰禁用态、蓝色主色符合方向。桌面侧栏220px（1199以下190px），内容最大900px；标题32px、正文15px/1.7，表单有label，文档卡片16px内边距，分页位于列表下方并可换行。任务区域有独立标题、文件信息、反馈与任务摘要。无大规模重设计。

1440、1024、768、390px均仅完成静态规则审查，视觉验收待确认：768仍为双栏，390为单栏；长文件名、原生file input和密集分页尤其需要截图确认，不能宣称无溢出。

按钮名称明确、原生disabled语义、focus-visible蓝色轮廓、role=status反馈均存在。任务查询会清空/更新状态反馈，但taskText本身没有aria-live，读屏任务变化效果待确认。行内表单不要求Esc；名称input可Enter提交，textarea保留换行。Tab顺序、取消/创建后隐藏表单的焦点恢复及实际读屏效果未验证，是人工验收重点。错误通过刷新文档、顶部重新加载、手动刷新任务提供恢复路径；上传未知结果提示先确认后重试。

## 浏览器能力探测

使用computer-use技能说明探测：@oai/sky可导入，但sky.list_apps返回“Computer Use native pipe is unavailable ... 系统找不到指定的文件 (os error 2)”。没有可连接的原生控制服务。未打开浏览器、未取得截图、未启动额外临时服务。HTTP/模拟DOM结果不计入真实浏览器通过项。

## Windows 11 + IntelliJ IDEA 人工验收步骤（待执行）

1. 使用Java 21和项目Maven配置，导入pom。先准备专用隔离PG/pgvector数据库及MinIO桶；依据仓库基础设施记录核实版本，不使用开发业务库。上传会写数据库和对象存储，即使Worker关闭也不是只读操作。没有隔离环境时只做静态访问，不执行创建上传。Flyway只针对隔离库执行；不要修改既有migration或local配置。
2. IDEA运行KnowledgeAssistantApplication，使用独立Run Configuration的环境变量/参数指向隔离资源。显式保持assistant.worker.enabled=false、assistant.model.enabled=false、assistant.model.online-authorized=false、assistant.embedding.baseline-authorized=false、assistant.rerank.enabled=false、assistant.rerank.online-authorized=false。不要启动Worker；先确认启动日志和有效配置。
3. 默认端口8080，访问 `http://localhost:8080/api/index.html#knowledge`。当前是spring.mvc.servlet.path=/api；不要以/index.html替代。若改端口仅替换端口。
4. F12开启Network的Preserve log、Disable cache，过滤Fetch/XHR。确认静态JS/CSS成功；知识库请求为/api/v1/knowledge-bases。Console清空后开始记录。
5. 点击新建，检查名称自动聚焦、label、Tab顺序；空名提交有反馈；输入专用测试名，Enter提交，Network应只有一次POST，成功后列表刷新并选中新库。取消后检查焦点；本页不是弹窗，不要求Esc。不可重复盲点提交未知结果。
6. 空库显示暂无文档。使用隔离测试数据至少21个文档，确认page=1/2、pageSize=20与total匹配，首页/上一页/下一页/末页禁用正确。切库应回第一页，快速切换不被旧响应覆盖。没有21项数据时分页视觉项记未执行，不以空列表替代。
7. 选择UTF-8非空md/txt（不超过2MiB），核对文件名与上传按钮；非法扩展名、空文件和超限文件禁用。连续点击上传只能一条POST，Request Payload为multipart且file只有一个，boundary由浏览器生成。新文件202，重复内容200并提示已有任务。保存脱敏请求结果。
8. Worker关闭时任务可能停留RECEIVED，不等待READY作为本步骤条件。Network检查GET /api/v1/import-tasks/{id}无并发轮询，正常间隔约3秒。页面隐藏/Hash切换暂停查询，回来恢复一条链。模拟状态应使用隔离测试桩，不写开发数据库伪造状态；READY/FAILED及网络失败自动化已有覆盖，真实浏览器仍待执行。
9. 切换#chat/#knowledge/#retrieval及浏览器前进后退，确认选择一致、事件不重复。刷新页面后任务卡不持久保存，使用文档列表核对摘要。断网检查错误恢复；上传超时先刷新确认，勿直接重传。
10. DevTools设备工具栏设1440、1024、768、390 CSS px，100%缩放并分别截图；另测试浏览器125%/200%缩放。核对导航、输入框、长文件名、分页、错误和任务区域无横向溢出。全程键盘Tab/Shift+Tab/Enter操作，检查焦点可见及隐藏表单后的焦点；读屏检查状态反馈。
11. Console不得有未处理异常；Network核对请求次数、响应码与requestId。失败保存浏览器版本、URL、视口与缩放、复现步骤、预期/实际、截图、时间、脱敏HAR、Console和服务日志。清除凭据及业务原文后共享。结束仅关闭本轮服务与专用临时资源，保留既有日志。

## 真实 Worker 发布链路：未执行，需用户另行决定

需隔离PG/pgvector与迁移、可用ES/IK及索引配置、MinIO桶/权限、模型端点/密钥/配额、匹配模型名与1024维快照。核对assistant.worker.enabled、assistant.elasticsearch.enabled、assistant.model.enabled及online-authorized等有效配置后才可授权开启；本轮未改开关或密钥。Rerank/RAG不在此流程验收范围，保持关闭。

Embedding按待处理chunk分批model.call，单次执行约ceil(待嵌入chunk数/批大小)次；分块800码点、重叠120，实际解析结果决定chunk数。Worker最多4次尝试，部分持久化与客户端重试会影响实际调用次数，精确请求总数未知。Token、供应商费率、重试费用和总金额未知，不能承诺免费。还会产生PG写入、MinIO存储与ES索引/查询；可能有环境资源费用。真实上传成功不等于发布成功，必须分别核对任务READY、文档PUBLISHED及索引一致性。未授权前不触发该链路。
