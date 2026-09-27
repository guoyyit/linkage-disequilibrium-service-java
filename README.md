# 连锁不平衡（Linkage Disequilibrium）本地计算与过滤服务

一个不依赖任何外部服务的本地遗传变异位点连锁不平衡（LD）计算与过滤服务。按**批次**导入带样本
与位点的基因型数据，导入前做位点过滤与样本完整性校验，计算位点间的**等位基因频率差**与
**基因型相关系数平方 r²**，并支持按染色体、位置窗口、次等位基因频率（MAF）、缺失率与连锁强度
阈值组合筛选位点。支持长任务的进度查询、取消与超时，支持进程重启后从本地文件恢复已提交批次。

技术栈：Java 17、Spring Boot 3.4、内存数据集 + 本地文件持久化、JUnit 5。

---

## 1. 运行与构建

```bash
mvn spring-boot:run        # 启动服务（默认端口 8080）
mvn test                   # 运行全部 45 个自动化测试
mvn package                # 打包
```

关键配置（`src/main/resources/application.properties`，均可用系统属性覆盖）：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `ld.data-dir` | `./data/batches` | 已提交批次的本地持久化目录 |
| `ld.import.default-min-maf` | `0.0` | 导入位点的默认最小 MAF |
| `ld.import.default-max-missing-rate` | `0.1` | 位点/样本默认最大缺失率 |
| `ld.job.default-timeout-ms` | `600000` | LD 任务默认超时（毫秒） |

---

## 2. 输入格式

### 2.1 导入批次 `POST /api/batches`

```json
{
  "batchId": "batch-001",
  "sampleIds": ["sample-1", "sample-2", "sample-3", "sample-4"],
  "variants": [
    {"id": "rs1", "chrom": "chr1", "position": 10500, "ref": "A", "alt": "T"},
    {"id": "rs2", "chrom": "chr1", "position": 10800, "ref": "G", "alt": "C"}
  ],
  "genotypes": [
    [0, 0],
    [1, 1],
    [1, 2],
    [2, 2]
  ],
  "filterOptions": {
    "minVariantMaf": 0.05,
    "maxMissingRate": 0.1,
    "minPosition": 10000,
    "maxPosition": 20000
  }
}
```

* `genotypes` 为**行=样本、列=位点**的矩阵，行列顺序分别与 `sampleIds`、`variants` 对应。
* 染色体写法 `chr1` 与 `1` 等价（内部规范化：去掉 `chr` 前缀并大写）。
* 样本与位点可**跨批次增量出现**：新批次可引入新样本、新位点；跨批次未覆盖的
  （样本, 位点）组合按**缺失**处理；已存在的缺失单元格允许由后续批次补全。
* `filterOptions` 可整段或逐字段省略，省略时使用服务端默认值。

### 2.2 基因型编码与缺失值语义（重要）

| 编码 | 含义 |
| --- | --- |
| `0` | 参考等位基因纯合（AA，alt 剂量 0） |
| `1` | 杂合（Aa，alt 剂量 1） |
| `2` | 变异等位基因纯合（aa，alt 剂量 2） |
| `null` 或 `-1` | **缺失基因型**（两者等价） |
| 其它整数值 | **非法编码，拒绝整个批次** |

缺失采用**成对可用（pairwise complete）**语义：计算某一对位点的 LD 时，只使用这两个位点都
非缺失的样本。因此：

* 任一对位点的指标只依赖该对位点的数据，**与导入批次的切分、请求次数、是否并发无关**，
  必然等于“同一数据全量重算”的结果；
* 某位点在某些样本缺失，不影响它与另一位点在共同可用样本上的 r²；
* 若一对位点没有任何共同可用样本，则频率差与 r² 记为 `0.0`、`n=0`。

---

## 3. 导入前校验与过滤规则（不满足即明确拒绝，绝不静默忽略）

校验在任何数据写入之前进行，任一项不满足都会返回 HTTP `422` 与**全部**可定位错误（每个错误
都标注 `批次 / 位点(id 与坐标) / 样本行号`），且不会产生任何部分写入：

**结构与编码**
* `batchId` 非空；样本列表、位点列表、基因型矩阵非空；
* 样本标识、位点标识在批次内不重复、非空；位点 `chrom/ref/alt` 合法、`position > 0`、`ref != alt`；
* 矩阵行数 = 样本数、每行长度 = 位点数；基因型只能是 `0/1/2/null/-1`。

**位点过滤**（`filterOptions`）
* 位置窗口：`minPosition <= position <= maxPosition`；
* 位点缺失率 `= 缺失样本数 / 样本总数 <= maxMissingRate`；
* 位点次等位基因频率 `MAF = min(p, 1-p)`，`p = alt 剂量和 / (2 × 非缺失样本数)`，
  需满足 `MAF >= minVariantMaf`；
* **单态位点（MAF = 0）一律拒绝**——无法定义连锁不平衡。

**样本完整性**
* 样本缺失率 `= 该样本缺失位点数 / 批内位点数 <= maxMissingRate`。

**跨批次一致性**
* 已提交的同名位点定义（染色体/位置/ref/alt）不得冲突；
* 同一（样本, 位点）已存在非缺失基因型时，新值必须一致，否则拒绝
  （已缺失、新值补全是允许的增量补全）。

**幂等**：相同 `batchId` 的重复导入直接返回 `alreadyPresent=true` 与当前版本，**不改变任何
结果**（即使重复请求携带的内容不同）。

错误响应示例：

```json
{
  "error": "BATCH_REJECTED",
  "batchId": "batch-001",
  "details": [
    "批次[batch-001] 位点[rs2 chr1:10800] 样本行#3 非法基因型编码 5（仅允许 0/1/2，null/-1 表示缺失）"
  ]
}
```

---

## 4. LD 指标与计算任务

### 4.1 指标定义（在成对可用样本上）

* 等位基因频率差：`|p1 - p2|`，`p = 平均 alt 剂量 / 2`；
* r²：两条 alt 剂量向量的 Pearson 相关系数平方（数值上裁剪到 `[0,1]`；若任一位点在成对
  样本上无方差，r² 记为 `0.0`）。

### 4.2 任务接口

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/ld/jobs?timeoutMillis=600000` | 提交两两 LD 计算；请求体为位点 id 数组，空数组/省略表示快照内全部位点。可固定 `snapshotVersion` |
| `GET /api/ld/jobs/{jobId}` | 进度查询：`PENDING/RUNNING/COMPLETED/CANCELLED/FAILED/TIMEOUT` 与 `progressPercent` |
| `POST /api/ld/jobs/{jobId}/cancel` | 协作式取消 |
| `GET /api/ld/jobs/{jobId}/results` | 取**完整**结果；未完成/取消/失败/超时返回空列表 |

**只执行一次（去重）**：任务身份 = `(快照版本, 规范化后的位点集合)`。相同集合（顺序不同也算
相同）的重复提交或并发提交返回同一个 `jobId`，计算只执行一次。

**取消 / 超时 / 失败不留部分结果**：计算线程在每对位点前检查取消标志与截止时间；只有全部
计算成功后才一次性发布结果，否则终态为 `CANCELLED/TIMEOUT/FAILED` 且 `results` 始终为空。

**快照隔离**：任务按提交时的快照版本绑定一个不可变快照执行，运行期间即使有新批次导入，
结果仍属于提交时的已提交状态。

结果体：

```json
{ "pairs": [ {"variant1":"rs1","variant2":"rs2","alleleFreqDiff":0.0,"rSquared":1.0,"n":4} ] }
```

---

## 5. 位点筛选 `POST /api/filter`

```json
{
  "chrom": "chr1",
  "windowStart": 10000,
  "windowEnd": 20000,
  "minMaf": 0.05,
  "maxMissingRate": 0.1,
  "maxLdR2": 0.2,
  "snapshotVersion": 3
}
```

* 所有字段可空，空表示该维度不限制；`snapshotVersion` 省略时基于最新已提交快照。
* 先按 **染色体 → 位置窗口 → MAF → 缺失率** 过滤候选位点；
* 若给定 `maxLdR2`，再做**确定性贪心 LD 剪枝**：候选位点按
  `(染色体规范化, 位置, id)` 排序后顺序保留，当且仅当某位点与**所有已保留**位点的 r²
  都 `<= maxLdR2` 才保留它。
* 返回结果携带所用 `snapshotVersion`。由于基于不可变快照、遍历顺序固定、r² 为纯函数计算，
  **相同快照 + 相同条件必然得到相同结果**，不会出现结果漂移。

快照接口：`GET /api/snapshot`（当前）、`GET /api/snapshot/{version}`（指定版本）。

---

## 6. 并发、隔离与崩溃恢复

* **提交原子性**：批次先写入 `ld.data-dir/NNNNNNNN-<batchId>.json.tmp` 临时文件，再用
  **原子 rename** 发布为正式文件（rename 即提交点），最后发布新的不可变快照。
* **读不脏写**：所有提交在同一把锁内串行；读路径只访问不可变快照与 `volatile` 引用，
  永远读不到部分批次或部分位点写入。计算/筛选开始时固定一个快照版本。
* **崩溃恢复**：进程启动（`ApplicationReadyEvent`）时扫描持久化目录：
  * 正式 `.json` 按文件名序号重放，重建样本/位点/矩阵与版本历史，之后可继续导入推进；
  * 崩溃遗留的 `.tmp` 残片会被删除——它们从未到达提交点，不会留下部分结果。

---

## 7. 自动化测试

`src/test/.../tests` 共 45 个用例：

| 测试类 | 覆盖需求 |
| --- | --- |
| `LdCalculatorTest` | 频率差/r² 数值正确性、成对缺失排除、与全量重算一致、确定性顺序 |
| `BatchValidationServiceTest` | 非法编码、维度不符、位点/样本缺失超阈、低 MAF、单态、窗口、重复、跨批次冲突、缺失补全 |
| `DatasetStoreTest` | 跨批次增量、版本不可变、重复提交幂等、重启恢复、崩溃 `.tmp` 残片清理 |
| `VariantFilterServiceTest` | 染色体/窗口/MAF/缺失率/LD 剪枝组合、可复现、非法条件、版本固定、空数据集 |
| `LdJobServiceTest` | 并发/重复提交只执行一次、进度、取消无部分结果、超时、未知任务/快照 |
| `LinkageServiceConcurrencyTest` | 幂等导入、422 定位错误不提交、并发导入与查询只见已提交快照 |
| `LinkageHttpTest` | HTTP 端到端：导入/重复/拒绝、筛选组合、LD 任务生命周期、404、重启恢复 |
| `ApplicationTests` | Spring 上下文装配 |
