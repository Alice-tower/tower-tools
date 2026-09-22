# 1.1.0 性能优化与实测

测试日期：2026-09-22。对照版本：提交 `47781d5` 的 1.0.0，与本次 1.1.0。

## 原则与实现

**性能和功能冲突时，优先保证功能完整性。** 本次没有改变扫描范围、Hidden / System / 重解析点过滤、Unicode 名称规则、AND / NOT 筛选、身份与标签保留、忽略、类型确认、重新定位合并或数据库删除边界。

- 扫描只读取当前 Root 的记录，提前建立待处理类型变化 ID 集合，消除“每个缺失资源都全库读取”的平方级工作。仍然完整枚举、校验成功后才在一个事务中提交，失败保持原记录。
- 复用最多 64 条预编译 SQL 语句；资源查询全部由 SQLite 过滤、排序、计数，再加载当前 200 条及其标签、待处理项。详情独立按 ID 读取。
- 数据库迁移到版本 2：增加规范化搜索列、排序索引、历史待处理项的资源索引。迁移事务性完成；稳定 ID、标签、状态和历史不变。排序采用与原 Kotlin 字符串比较一致的规则，避免 Unicode 补充字符排序变化。
- 界面保留 `LazyColumn`，另加数据库分页。跨页选择保留；“全选结果”会查询全部匹配 ID，批量标签不局限于当前页。Root 移除和 Tag 删除确认显示全库统计，不使用当前页计数。
- 后台查询使用 150 ms 防抖；过期结果不覆盖新筛选。导航不再全库刷新。可见行用 ID 索引查找标签、Root 和待处理信息。
- DOS 属性读取同时提供普通属性，减少一次重复文件属性读取；仍保留不跟随链接及规范路径检查。

## 环境与方法

- Windows x64，Intel Core i7-13700KF，Azul Zulu JDK 21；两个版本统一使用 `-Xmx1g`。
- SQLite JDBC 3.50.3.0；无新增或升级第三方依赖。
- 使用隔离数据库和测试文件，未扫描用户资料 Root，也未迁移用户正在使用的数据库。
- 数据处理基准：1 万 / 5 万资源，20 个标签，每资源 3 个关联，所有资源初始有 New 待处理项。普通 / 待处理查询各预热一次，再分别记录 5 次普通、待处理和组合筛选耗时，取中位数。
- 组合筛选为名称子串 + 两个 Include + 一个 Exclude。旧版读取全库并执行原内存筛选；新版按完整条件统计总数并取第一页 200 条。表中的查询耗时是数据准备，不包含 150 ms 输入防抖和 GUI 绘制。
- 重复扫描和全部缺失扫描的数据处理基准使用相同模拟枚举结果，以隔离目录 I/O；另外单独测试真实磁盘的完整扫描。扫描和单条修改只计一次，不能解读为稳定的延迟上界。
- 批量缺失旧版设 30 秒观察上限，到时仅终止该隔离测试 JVM；标注 `>30,000`，没有估算其最终耗时。

## 数据处理实测

单位：毫秒；越低越好。

| 场景 | 1 万：旧版 | 1 万：新版 | 5 万：旧版 | 5 万：新版 |
| --- | ---: | ---: | ---: | ---: |
| 普通列表查询，中位数 | 43.583 | 1.953 | 223.937 | 3.612 |
| 待处理列表查询，中位数 | 164.636 | 5.855 | 5,414.602 | 24.145 |
| 名称 + AND / NOT 筛选，中位数 | 43.504 | 10.773 | 230.350 | 47.426 |
| 无变化扫描的数据处理 | 328.235 | 182.631 | 1,465.975 | 748.873 |
| 添加单个标签并刷新列表 / 统计 | 47.229 | 9.872 | 230.033 | 30.114 |
| 全部资源缺失的数据处理 | >30,000 | 315.803 | >30,000 | 1,472.841 |

旧版 5 万条待处理查询的五次结果为 6,308.984 / 5,659.223 / 5,414.602 / 3,264.595 / 5,375.460 ms；新版为 24.969 / 24.473 / 24.145 / 24.102 / 24.087 ms。实际环境、缓存与运行负载会影响结果。

## 真实文件扫描

另建 1 万和 5 万个真实空文件，两版依次扫描同一目录；在完整扫描前各做三次枚举。保留全部文件属性与路径校验，不读取文件内容。

| 场景 | 1 万：旧版 | 1 万：新版 | 5 万：旧版 | 5 万：新版 |
| --- | ---: | ---: | ---: | ---: |
| 文件枚举 / 属性检查，中位数 | 1,498.851 ms | 1,428.428 ms | 7,771.004 ms | 7,258.191 ms |
| 首次完整扫描并入库 | 1,783.898 ms | 2,014.991 ms | 9,561.470 ms | 10,946.124 ms |
| 重复完整扫描 | 1,765.458 ms | 1,609.606 ms | 9,587.236 ms | 8,418.983 ms |

**首次入库变慢约 13–14%。** 新版需要保存规范化搜索键并维护额外索引；此次没有以取消数据校验或事务保护来抵消成本。后续列表查询显著加快，但完整扫描仍必须检查每个子项，所以不会像列表查询一样提升几十倍。

## 内存

保持当前列表所需的数据状态并请求 GC 后，测得 JVM 已用堆内存：

| 数据规模 | 旧版 | 新版 |
| --- | ---: | ---: |
| 1 万资源 | 14.043 MiB | 4.021 MiB |
| 5 万资源 | 55.868 MiB | 4.059 MiB |

这不是完整 GUI 进程的总内存，也不包含 SQLite / 图形原生内存。旧版保留完整快照及筛选结果；新版保留当前页和全局元数据。扫描临时表、模拟枚举列表已释放后才测量此项；实际完整扫描、全选 ID 集合仍随资源数增长，不能认为整个程序始终只占 4 MiB。

## 功能回归

28 项测试通过，0 失败、0 跳过：

- 原有 22 项功能测试保留；异步界面测试等待后台查询完成后断言，未删除既有场景。
- SQL 与内存参考实现逐页一致：Root、类型、状态、无标签、待处理、AND / NOT、组合条件、Unicode、`%` / `_` 字面子串、空结果和越界页。
- 1 万条资源的扫描、缺失、提醒去重、恢复、标签保留和中途数据库异常的完整事务回滚。
- 旧 schema v1 升级保留 ID、时间、标签别名、状态、Root 错误和已处理历史；重启查询正常。
- GUI 使用 450 条记录验证跨页选择、详情保留、全部结果批量标签和连续输入。
- 过期查询不会覆盖新查询；只读导航不刷新数据；数据库打开失败不会导致无限加载。

测试报告：`build/reports/tests/test/index.html`。测试日志的 `LOCALAPPDATA` 已隔离到 `build/test-local-data`。

## 复现

基准源码保存在 `src/performance/java/Benchmark.java` 和 `RealFilesBenchmark.java`。它们仅用于测试，不进入应用运行时。先构建目标版本，或保留旧版便携包中的 `app/` 目录。在仓库根目录执行以下示例，数据目录必须是一个新的测试目录，避免重复 seed：

```powershell
$perfApp = 'outputs/tools/dev.towertools.resourcetagger/app'
$perfRun = 'outputs/.performance/resource-tagger/reproduce'
New-Item -ItemType Directory -Force -Path "$perfRun/classes" | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp "$perfApp/*" -d "$perfRun/classes" apps/resource-tagger/src/performance/java/Benchmark.java apps/resource-tagger/src/performance/java/RealFilesBenchmark.java
& "$env:JAVA_HOME/bin/java.exe" -Xmx1g -cp "$perfApp/*;$perfRun/classes" Benchmark seed 10000 "$perfRun/data"
& "$env:JAVA_HOME/bin/java.exe" -Xmx1g -cp "$perfApp/*;$perfRun/classes" Benchmark run 10000 "$perfRun/data"
& "$env:JAVA_HOME/bin/java.exe" -Xmx1g -cp "$perfApp/*;$perfRun/classes" Benchmark missing 10000 "$perfRun/data"
& "$env:JAVA_HOME/bin/java.exe" -Xmx1g -cp "$perfApp/*;$perfRun/classes" RealFilesBenchmark create 10000 "$perfRun/real"
& "$env:JAVA_HOME/bin/java.exe" -Xmx1g -cp "$perfApp/*;$perfRun/classes" RealFilesBenchmark measured 10000 "$perfRun/real"
```

旧版全部缺失基准可能运行很久；本次使用独立进程和 30 秒上限。改为 50000 即可测试五万条；必须使用新的数据库目录或新的 `measured` 名称。先完成基准进程再重新构建便携输出，避免 JAR 被占用。

本次原始数据位于 `outputs/.performance/resource-tagger/results/`，该目录不提交 Git。最终表格使用 `final-baseline-*`、`final2-optimized-*`、`before-missing-*`、`final2-missing-*`、`real-baseline-*`、`real-final-optimized-*`。

## 当前边界

名称子串查询、精确总数统计和很靠后的 OFFSET 页仍可能随资料库规模增长；没有改变成前缀匹配或近似统计。所有写入操作串行，长扫描期间新查询会排队，但界面线程不执行扫描或 SQL。标签定义仍完整加载，尚未专门测试数万个不同标签。以上测试不代表网络盘、机械盘或十万以上资源的性能承诺。
