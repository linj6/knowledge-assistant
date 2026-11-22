# 实验06实际验证记录

日期：2026-10-22，Windows11 / PowerShell7 / Java21.0.10 / Maven3.9.16 / Docker Desktop。
`mvn -B -ntp -Plive verify` 最终 BUILD SUCCESS，12项单测+2组真实组件参数化IT，失败/错误/跳过均0，原始证据 [live.txt](live.txt)。该日志同时包含完整Surefire单测结果。测试结束18:03:58（Asia/Shanghai）。

- 单测：5项发布Confirm/Return/NACK/超时/连接异常、4项处理顺序/指标/异常不ACK/延时边界、3项H2事务幂等/冲突/回滚；H2不充当MySQL实测证据。
- 真实MySQL+RabbitMQ+Management：参数(1,1)对应RunId `it-376f1145-b134-4c8e-8bcb-3f2ec575ce75`；参数(4,2)对应 `it-3fd83d27-ce00-4306-8de7-8d801a97ab60`。每组24条独立事件，初始Ready=24，处理门闩暂停时Unacked分别为1、8，活跃处理数分别1、2，实际消费者数匹配配置；停止后Unacked=0且Ready>0，再启动最终Ready/Unacked=0，唯一事件24条、积分总和600。以上均由测试断言确认，不依据“启动成功”。
- 恢复过程为逻辑停止监听容器、关闭投递通道后重入队，没有OS kill或网络分区。
- 独立项目bil-rabbitmq-lab06，13321/5678/15678；Volume及测试数据全部保留。

## 首轮失败与修复

新Volume Cookie属主导致RabbitMQ eacces；bootstrap仅修复该文件元数据后启动成功，未删除数据。首次IT使用 `disable_stats=true`，Management响应不返回consumers字段；测试把缺失字段读成0而失败，见 [live-first.txt](live-first.txt)。改为保留统计并显式对缺失字段读-1，随后两组通过。故障注入门闩在finally中释放，保证失败时也能安全关闭上下文。
首次失败留下两个独立队列，Ready分别23和22，均未清理；其RunId与最终通过的两组不同，不能混淆验收计数。

## 未执行与边界

`Measure-Backlog.ps1`已做PowerShell语法检查，尚未运行人工性能对照采样；没有报告未经测量的吞吐率/延迟分位数。IT日志中的小样本耗时不作为性能结果。meanHandlerToAckMs是成功ACK回调的平均处理耗时，包含采样前日志开销；不含队列等待，不是端到端延迟。
IDEA点击启动、人工Management观察、大量消息/资源饱和、真实进程崩溃、网络故障均待未来执行。入口见README和Measure-Backlog.ps1。本轮无提交/推送，无删除Volume。
