# 构建编排引擎（build-orchestration-engine）

本仓库提供一套**声明式构建描述的解析与增量执行引擎**。工程描述与动作输入用本地
JSON 文件或内存数据模拟，整条链路只发生在本地进程与磁盘上，不依赖任何外部服务。

## 快速开始

```java
BuildSpec spec = new BuildSpecParser().parse(Path.of("build.json"));
new SpecValidator().validate(spec);                 // 有任何问题整体拒绝
DependencyGraph graph = new DependencyGraph(spec);

BuildEngine engine = new BuildEngine(
        spec, graph,
        new CompositeInputSource(new MemoryInputSource(), new DiskInputSource(srcRoot)),
        outputRoot,                                 // 产物根目录
        new BuildStateStore(stateDir),              // 增量/恢复状态
        new LocalDiskCache(cacheDir),               // 可校验缓存
        new EngineConfig().setParallelism(4));      // 运行配置

BuildReport report = engine.run(Set.of(), false);   // 空集合 = 全部目标
report.target("gen").ifPresent(r ->
        System.out.println(r.getStatus() + " / " + r.getReason() + " / " + r.getDurationMs() + "ms"));
```

## 工程描述格式（build.json）

```json
{
  "version": 1,
  "toolchains": { "jdk": "17" },
  "targets": [
    {
      "name": "gen",
      "toolchain": "jdk",
      "deps": ["base"],
      "inputs": ["src/in.txt"],
      "outputs": ["gen.out"],
      "resources": ["exclusive-io"],
      "timeoutMs": 5000,
      "retries": 1,
      "action": { "kind": "CONCAT", "params": {} }
    }
  ]
}
```

- `deps`：依赖的目标名；`inputs`：输入（内存数据或磁盘相对路径）；`outputs`：
  产物相对路径；`resources`：需要独占的命名资源；`timeoutMs`/`retries` 可覆盖全局默认。
- 动作类型：`WRITE`（写入 `params.content`）、`CONCAT`（按声明顺序拼接全部输入）、
  `COPY`（复制第一个输入）、`SLEEP`（睡眠 `params.ms`，用于并发模拟）、
  `APPEND`（向 `params.file` 追加一行副作用日志，`params.fail=true` 时随后失败）、
  `FAIL`（总是失败）。

### 校验语义（整体拒绝）

`SpecValidator` 收集全部问题后一次性抛出 `SpecException`，**不会加载一半**：
循环依赖（报出完整环路径，如 `a -> b -> c -> a`）、引用不存在的目标（定位到引用方）、
重复定义目标、产物被多个目标占用、引用未声明的工具链、目标没有产物。

## 执行顺序

`DependencyGraph.executionOrder()` 使用 Kahn 算法，就绪集合中的并列目标一律按
**声明下标**（而非字典序、哈希表或文件系统顺序）出队，因此同一份描述在任意并发度、
任意遍历顺序下，满足依赖约束的执行次序都是确定且可复现的。调度器提交任务也遵循同一
优先级，`BuildReport.resultsInOrder()` 始终按该稳定顺序汇报。

## 增量语义

每个目标的**指纹**由以下部分 SHA-256 而成：

1. 目标名、工具链名与版本；
2. 动作类型与规范化后的参数（按参数名排序）；
3. 每个声明输入的路径 + 内容哈希（内存覆盖层优先，其次磁盘）；
4. 全部直接依赖的指纹（递归折叠间接依赖）。

判定结果（`TargetResult.status` / `reason`）：

| 状态 | 含义 |
|---|---|
| `UP_TO_DATE` / `INPUTS_UNCHANGED` | 指纹与已提交状态一致且产物哈希未变，跳过 |
| `EXECUTED` / `FORCED_RERUN` | 强制重跑（`run(..., true)`），绕过缓存真正执行 |
| `EXECUTED` / `NO_PRIOR_STATE` | 无已提交状态（首次或状态丢失） |
| `EXECUTED` / `INPUTS_CHANGED` | 指纹变化（自身输入/动作/工具链或依赖变化） |
| `EXECUTED` / `OUTPUT_MISSING` | 产物被外部删除 |
| `EXECUTED` / `OUTPUT_TAMPERED` | 产物被外部改动（哈希不匹配），会被识别并重跑 |
| `CACHE_HIT` / `CACHE_RESTORED` | 从校验通过的缓存还原（原始判定原因保留在 `detail`） |
| `FAILED` / `ACTION_FAILED` 或 `TIMEOUT` | 动作失败或超时 |
| `CANCELLED` / `CANCELLED_BY_FAILURE`、`DEPENDENCY_FAILED` | 被失败策略取消 |

只有指纹变化、产物缺失/被篡改或强制重跑时目标才会执行；依赖的指纹会传导，
因此改动会精确地沿依赖边传播，未受影响的目标一律跳过。

## 缓存

- **键**即上述目标指纹，因此命中不可能映射到过期或错误的产物。
- 每个缓存条目 = `manifest.json`（键 + 产物路径→SHA-256）+ 按内容寻址的产物 blob。
- 查询时逐个校验 blob 哈希；清单损坏、blob 损坏、键不匹配一律判定为
  `CORRUPT`，条目被丢弃并**回退到重新执行**，绝不静默使用坏缓存；
  还原产物后还会再次校验写入结果。
- `cacheMaxEntries` 控制保留策略：按创建时间淘汰最旧条目。
- `cacheEnabled=false` 时完全绕过缓存（只影响后续执行）。

## 并发与资源

- `parallelism` 控制线程池大小；无依赖关系的目标并行推进。
- 声明了同一 `resources` 的动作通过公平互斥锁串行（多资源按名排序加锁，避免死锁）。
- 失败策略：`CANCEL_REMAINING`（默认，取消其余目标）或 `CONTINUE`（仅取消失败者的
  传递依赖，其余继续）。
- 动作只写入暂存目录 `out/.staging/<target>`，成功后原子提交到产物树；失败时暂存
  目录被清理，**不会留下半成品产物**。
- 超时通过独立工作线程 + `join(timeout)` 实现，超时即中断并按 `retries` 重试。

## 中断与恢复

- 每个目标成功后，其状态（指纹 + 产物哈希 + committed 标记）以**临时文件 + 原子改名**
  的方式写入状态目录，这就是恢复点。
- 进程重启后：有已提交状态且指纹一致的目标直接判 `UP_TO_DATE`，**带副作用的动作不会
  重复执行**；没有已提交状态的目标（含状态文件损坏的情况，损坏状态一律视为不存在）
  从头重跑，暂存目录先清空。
- 已完成与未完成目标可随时通过 `BuildStateStore.load(name)` 区分。

## 配置项（EngineConfig）

| 配置 | 默认 | 说明 |
|---|---|---|
| `parallelism` | 1 | 并发执行线程数 |
| `cacheEnabled` | true | 缓存开关 |
| `cacheMaxEntries` | 128 | 缓存保留条目上限（淘汰最旧） |
| `defaultTimeoutMs` | 60000 | 动作默认超时（目标可用 `timeoutMs` 覆盖） |
| `defaultRetries` | 0 | 动作默认重试次数（目标可用 `retries` 覆盖） |
| `failurePolicy` | CANCEL_REMAINING | 失败时取消其余目标或继续 |

配置在构造 `BuildEngine` 时注入，**只影响后续执行**，不影响已完成的运行。

## 测试

```
mvn test
```

覆盖：循环依赖/悬空引用/重复定义（`SpecValidationTest`）、确定性顺序
（`DeterministicOrderTest`）、增量命中与失效、产物篡改（`IncrementalTest`）、
并发与资源独占、失败策略、超时重试（`ConcurrencyResourceTest`）、缓存损坏与保留策略
（`CacheCorruptionTest`）、中断恢复（`RecoveryTest`）。
