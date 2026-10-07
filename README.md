# 本地声明式构建编排引擎

该模块提供一个只在当前 Java 进程和本地磁盘内工作的声明式构建描述解析与增量执行引擎，不依赖外部服务。

## 快速开始

```java
EngineConfig config = EngineConfig.builder()
        .concurrency(4)
        .cacheEnabled(true)
        .retention(new CacheRetention(200L, Duration.ofDays(30)))
        .failureStrategy(FailureStrategy.CANCEL)
        .build();

BuildEngine engine = BuildEngine.builder()
        .workspace(Path.of("/path/to/workspace"))
        .config(config)
        .toolchain(new MyToolchain())
        .build();

BuildResult result = engine.run(Path.of("build.json"));
TargetResult target = result.target("compile");
target.status();      // SUCCESS / UP_TO_DATE / CACHED / FAILED / SKIPPED / CANCELLED
target.reason();      // INPUT_CHANGED / OUTPUT_MODIFIED / CACHE_HIT ...
target.duration();    // 本次判定与执行耗时
```

也可以直接传入内存中的 `BuildDescription`，不经过文件。

## 工程描述

描述格式为 JSON，未知字段会导致解析失败：

```json
{
  "name": "example",
  "targets": [
    {
      "name": "compile",
      "dependsOn": ["generate"],
      "inputs": ["src/main/java"],
      "outputs": ["build/classes"],
      "toolchain": "javac-local",
      "arguments": ["-parameters"],
      "environment": {
        "RELEASE": "17"
      },
      "exclusiveResources": [],
      "timeout": "PT5M",
      "retries": 1
    }
  ]
}
```

字段语义：

- `name`：目标名，必须非空且在同一描述中唯一。
- `dependsOn`：依赖目标名，目标必须存在，不能形成自环或循环。
- `inputs` / `outputs`：相对工作区的路径，不能是绝对路径或通过 `..` 逃逸。每个目标至少声明一个产物；不同目标不能声明同一输出。
- `toolchain`：已在 `ToolchainRegistry` 注册的动作实现。
- `arguments` / `environment`：工具链参数和环境变量，也参与动作指纹。
- `exclusiveResources`：逻辑独占资源名，例如数据库、设备或固定端口。
- `timeout`：ISO-8601 duration，例如 `PT30S`；为空时使用全局默认值。
- `retries`：失败后的额外重试次数；为空时使用全局默认值。

解析与校验是原子的：重复目标、未知目标、循环依赖、未知工具链、非法路径、重复产物等问题会一次性收集并抛出 `BuildValidationException`。异常中的每个 `BuildIssue` 都带目标名；存在任一问题都不会执行动作。

## 稳定执行顺序

规划器使用声明序号（即 JSON/内存列表中的位置）执行稳定的拓扑排序：

1. 无未满足依赖的目标进入就绪队列；
2. 就绪队列按声明序号排序，而不是按名称字典序；
3. 无依赖关系的目标可并行，但结果列表和可复现的派发次序仍由声明序号决定；
4. 遍历结果不受文件系统目录遍历顺序影响。

依赖成功、缓存恢复或判定为最新后才会解锁下游；依赖失败时，其传递下游会被标记为 `SKIPPED / DEPENDENCY_FAILED`。

## 增量语义

每次执行按以下顺序判定：

1. `FORCED_REBUILD`：全局配置要求强制重跑。
2. `NO_PREVIOUS_STATE`：没有已提交状态。
3. `INPUT_CHANGED`：输入文件集合或任一文件内容变化。
4. `ACTION_CHANGED`：工具链、参数、环境变量、输出声明或独占资源声明变化。
5. `DEPENDENCY_CHANGED`：任一依赖的已提交输出指纹变化。
6. `OUTPUT_MISSING`：声明的输出文件不存在。
7. `OUTPUT_MODIFIED`：输出存在但与最近提交的指纹不同，可识别外部改动。
8. `UP_TO_DATE`：以上均未发生，跳过动作。

文件指纹使用规范化相对路径、文件大小和 SHA-256 聚合。路径排序是确定的，目录枚举顺序不会影响指纹。符号链接不会被当作可指纹化输入或产物。

## 缓存键与校验

缓存键的逻辑构成包括：

- 固定 schema 版本；
- 目标名；
- 输入文件集合与内容指纹；
- 动作指纹：工具链、参数、环境变量、输出集合和资源声明；
- 所有依赖目标的输出指纹；
- 输出路径集合。

本地缓存位于 `.build-engine/cache`，每个条目包含：

- 产物文件；
- 输出路径及 SHA-256；
- 目标名与缓存键；
- 创建时间；
- manifest 自身校验和。

命中前会校验：缓存目录、manifest 校验和、schema、缓存键、目标名映射、产物存在性、符号链接以及恢复后的逐文件哈希。清单损坏、产物哈希不匹配、目标映射错误或键不一致时不会静默使用缓存，而会重新执行动作，判定原因为 `CORRUPT_CACHE_FALLBACK`。

工作区产物被外部修改时不会用缓存静默覆盖后报“跳过”，该场景必须重跑动作并返回 `OUTPUT_MODIFIED`；仅删除产物时，可以从有效缓存恢复并返回 `CACHED / CACHE_HIT`。

## 原子产物、失败与并发

- 工具链在 `.build-engine/staging/<target-index>` 的独立暂存目录中执行。
- 动作必须生成全部声明的普通文件，否则视为失败。
- 发布到工作区时按输出逐个备份并替换；中途失败会回滚已发布文件并清理暂存区，不留下半成品。
- `exclusiveResources` 在内部按资源名排序后申请，同一资源任何时刻只有一个动作持有；这同时避免多资源锁顺序死锁。
- `FailureStrategy.CANCEL`：任一目标失败后不启动新的可取消目标，依赖链标记为跳过。
- `FailureStrategy.CONTINUE`：继续运行无依赖关系的目标，但失败目标的传递依赖仍跳过。
- 目标级 `timeout` 到期会中断动作线程；`retries` 表示额外尝试次数。

## 中断与重启恢复

每个目标有四阶段事务：

1. `RUNNING`：创建暂存目录并开始动作；
2. `STAGED`：动作完成，暂存产物哈希已记录；
3. `PUBLISHED`：产物已原子发布到工作区；
4. 状态文件原子写入并删除该目标事务后才完成提交。

进程重启时：

- 无事务且状态匹配：按增量规则跳过或执行；
- `RUNNING`：动作未证明完成，清理暂存区并重新执行；
- `STAGED` 且暂存产物仍与事务和当前输入匹配：不重复动作，继续发布并提交；
- `PUBLISHED` 且工作区产物与事务匹配：不重复动作，只补写状态并提交；
- `PUBLISHED` 但产物丢失、哈希不符、缓存键不符或输入已变：拒绝旧事务并重新执行。

状态文件和日志均采用临时文件加原子替换。已完成和未完成状态可通过 `state.json` 与 `journal.json` 明确区分。

外部线程调用 `BuildEngine.close()` 会中断正在执行的动作和调度循环，构建结果标记为 `interrupted=true`，当前目标为 `CANCELLED / INTERRUPTED`，未发布的产物不会进入工作区。

## 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `concurrency` | `1` | 最大并行目标数，必须大于 0。 |
| `cacheEnabled` | `true` | 是否启用内容寻址缓存；关闭后不读取也不写入缓存。 |
| `retention.maxEntries` | 不限 | 最多保留缓存条目数，按创建时间淘汰较旧项。 |
| `retention.maxAge` | 不限 | 缓存最大保留时间。 |
| `defaultTimeout` | 无超时 | 未声明 `timeout` 的目标使用该值。 |
| `defaultRetries` | `0` | 未声明 `retries` 的目标使用该值。 |
| `failureStrategy` | `CANCEL` | 失败后取消或继续独立目标。 |
| `forceRebuild` | `false` | 强制忽略增量与缓存，但仍重新计算依赖。 |

`EngineConfig` 是不可变 record。配置在构建 `BuildEngine` 时捕获，只影响该实例及后续执行，不会回写历史状态。

## 查询结果

`BuildResult` 提供：

- `success()`：是否无失败、跳过或取消；
- `interrupted()`：是否由外部中断结束；
- `targets()`：按稳定执行顺序排列的目标结果；
- `target(name)`：按目标名查询。

`TargetResult` 提供目标状态、判定原因、耗时、尝试次数、是否来自缓存、最终输出指纹和失败信息。

## 测试覆盖

自动化测试覆盖：

- 重复定义、未知依赖、自依赖和循环依赖；
- 声明顺序稳定和依赖先后；
- 首次执行、跳过、输入/动作/依赖变化、产物缺失和外部篡改；
- 并发执行与独占资源；
- 缓存命中、manifest 损坏、缓存产物损坏、缓存关闭和保留策略；
- 失败取消、继续独立目标、依赖传递跳过和无半成品；
- 超时、重试；
- 外部中断；
- 进程重启后从事务恢复或必要时重跑。
