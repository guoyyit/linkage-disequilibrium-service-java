# 连锁不平衡计算与过滤服务（Linkage Disequilibrium Service）

本地运行的遗传变异位点连锁不平衡（Linkage Disequilibrium, LD）计算与过滤服务。
接收多批次二倍体基因型数据，在导入前完成位点/样本质量校验，计算位点间的
**等位基因频率差（MAF difference）**与**相关系数平方（r²）**，并按可配置、
可复现的规则输出位点集合。所有数据通过本地文件或内存 JSON 提供，无外部服务依赖。

- Java 17 + Spring Boot 3.4，开箱即用：`mvn spring-boot:run`
- 默认端口 `8080`
- 持久化批次目录：`data/batches`（可用 `ld-service.storage.dir` 配置）

---

## 1. 数据模型与输入格式

### 1.1 位点（Variant）

```json
{ "chromosome": "chr1", "position": 100, "referenceAllele": "A", "alternateAllele": "T" }
```

位点由 `(chromosome, position, referenceAllele, alternateAllele)` 唯一标识；
位置为正整数。计算与筛选时位点按
`(chromosome, position, referenceAllele, alternateAllele)` 的全序确定遍历顺序，
保证结果确定性。

### 1.2 内存批次 JSON：`POST /api/batches`

```json
{
  "batchId": "cohort-2026-01",
  "samples": ["s1", "s2", "s3"],
  "variants": [
    {"chromosome": "chr1", "position": 10, "referenceAllele": "A", "alternateAllele": "T"},
    {"chromosome": "chr1", "position": 20, "referenceAllele": "G", "alternateAllele": "C"}
  ],
  "genotypes": [
    [0, 1, 2],
    [-1, 0, 1]
  ],
  "options": {
    "allowedChromosomes": ["chr1"],
    "minPosition": 0,
    "maxPosition": 0,
    "sampleMissingThreshold": 0.1,
    "variantMissingThreshold": 0.1
  }
}
```

- `genotypes[r][c]` 与 `variants[r]`、`samples[c]` 对齐，行数必须等于位点数，
  每行长度必须等于样本数。
- **基因型编码（等位基因剂量 dosage）**：`0`、`1`、`2` 表示替代等位基因拷贝数；
  `-1` 表示缺失（见 1.4）。

### 1.3 本地文件 TSV：`POST /api/batches/{batchId}/tsv`（multipart 字段 `file`）

```
## 可选的元信息行（以 ## 开头会被忽略）
#CHROM	POS	REF	ALT	s1	s2	s3
chr1	10	A	T	0/0	0/1	1/1
chr1	20	G	C	.	0|0	0|1
```

- 第一列为 `#CHROM`（或不带 `#` 的 `CHROM`）的行为表头；以 `#` 开头但不是
  `#CHROM` 表头的行是注释。前 4 列为固定列，之后每列一个样本。
- 示例文件见 `examples/sample-batch.tsv`。
- TSV 上传使用默认导入选项（阈值 0.1）；需要自定义过滤规则时使用 JSON 接口。

### 1.4 缺失值语义

TSV 单元格接受以下令牌（大小写不敏感）：

| 含义 | 令牌 |
| --- | --- |
| 剂量 0 / 1 / 2 | `0`、`1`、`2`；或二倍体等位基因对 `0/0`、`0|1`、`1/1`、`0/1` … |
| 缺失 | 空字符串、`.`、`./.`、`NA`、`N`、`?` |

非法令牌（如 `3`、`0/2`、`A/B`）会被**明确拒绝**，错误带行号与列号。

跨批次合并时，缺失表示“未观测”：

- 缺失值**永远不会覆盖**任何已有值，也不会阻止另一个批次填充该
  `(variant, sample)` 单元；
- 仅当两个批次对同一 `(variant, sample)` 提供**不同的非缺失剂量**时才判定为冲突
  （错误码 `GENOTYPE_CONFLICT`），批次被整体拒绝。

---

## 2. 导入与质量过滤

### 2.1 导入选项 `options`

| 字段 | 含义 | 关闭方式 |
| --- | --- | --- |
| `allowedChromosomes` | 染色体白名单；不在其中的位点被拒绝 | 空数组 |
| `minPosition` / `maxPosition` | 位点位置必须落在闭区间 `[min,max]` | `0` |
| `sampleMissingThreshold` | 样本缺失率上限（超过则拒绝该批次） | `1.0` |
| `variantMissingThreshold` | 位点缺失率上限（超过则拒绝该批次） | `1.0` |

阈值取值范围 `[0,1]`。所有问题会被一次性收集并返回
`422 Unprocessable Entity`，错误体 `details` 中每项带可定位信息
（`batchId`、`row`、`column`、`sample`、`variant`、`code`、`message`），
**绝不静默丢弃**记录。

主要错误码：`SAMPLE_DUPLICATED`、`VARIANT_DUPLICATED`、`ROW_SHAPE`、
`GENOTYPE_INVALID`、`VARIANT_CHROMOSOME_REJECTED`、`VARIANT_POSITION_REJECTED`、
`SAMPLE_MISSING_TOO_HIGH`、`VARIANT_MISSING_TOO_HIGH`、`GENOTYPE_CONFLICT`。

### 2.2 幂等与增量

- `batchId` 是幂等键。重复导入**完全相同**的批次（样本、位点、基因型一致）
  返回 `duplicated=true`，数据不变、快照版本不变；并发的相同请求只会提交一次。
- 相同 `batchId` 但内容不同 → `409 Conflict`。
- 样本与位点可跨批次增量出现；合并采用“先到先得、缺失不覆盖”。

### 2.3 持久化与崩溃恢复

每个已提交批次先写入 `<dir>/<batchId>.json.tmp` 并 fsync，再**原子 rename**
为 `<dir>/<batchId>.json`，最后切换内存快照。因此：

- 崩溃只可能留下：无文件 / 完整 tmp（恢复时忽略并清理）/ 完整正式文件；
  不会留下部分批次结果。
- 启动时（`StartupRecovery`）按提交时间重放正式文件重建快照，
  已提交批次状态可恢复，随后可继续导入与计算。

---

## 3. 连锁不平衡计算

### 3.1 指标

对同一染色体内、位置距离不超过 `windowBp`（`0` 表示不限）的位点对：

- **r²**：两行替代等位基因剂量的皮尔逊相关系数平方，使用**成对完整样本**
  （两行都非缺失的样本）计算；共同非缺失样本少于 2 或任一行方差为 0 时 r²=0。
- **MAF difference**：两个位点次要等位基因频率之差的绝对值。频率来自快照构建时
  对非缺失样本的统一统计（`alleleFrequency = Σdosage / (2 × 非缺失样本数)`，
  `maf = min(af, 1-af)`），与成对计算相互独立。

### 3.2 任务接口（支持进度、取消、超时）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/ld/computations` | body `{ "taskId": "...", "windowBp": 0, "timeoutMillis": 0 }`；启动或加入计算 |
| GET | `/api/tasks/{id}` | 查询状态与进度（0–100） |
| GET | `/api/tasks/{id}/result` | 阻塞等待终态并返回 LD 矩阵 |
| DELETE | `/api/tasks/{id}` | 协作式取消 |

- **去重键为 `(快照版本, windowBp)`**：对同一已提交状态的重复或并发请求会合并到
  同一个任务，计算器只执行一次；另给的 `taskId` 成为该计算的别名。
- 计算在**提交时捕获的不可变快照**上运行，导入与计算同时进行时，计算结果必定
  属于某个已提交状态，不会被新批次影响。
- 超时会中断工作线程并将任务置为 `TIMEOUT`；取消置为 `CANCELLED`。失败/取消/
  超时不会缓存结果，也不会产生部分 LD 矩阵，可用同一键重新发起。
- 任务态保存在内存中；进程退出后任务消失，但批次已持久化，可恢复后重算。

---

## 4. 位点筛选：`POST /api/variants/filter`

请求体（任一值为 0/空表示不启用该规则）：

```json
{
  "chromosome": "chr1",
  "start": 0,
  "end": 1000000,
  "minMaf": 0.05,
  "maxMissing": 0.05,
  "maxLdR2": 0.2,
  "windowBp": 500000,
  "ldTaskId": null
}
```

1. 先按染色体、位置闭区间、最小 MAF、最大缺失率过滤；
2. 若 `0 < maxLdR2 ≤ 1`，触发/复用同快照的 LD 矩阵，按固定全序做**贪心剪枝**：
   依次考虑位点，若它与同染色体窗口内任一已保留位点的 `r² > maxLdR2` 则移除。

整个筛选在**单个不可变快照**上进行：相同条件组合结果完全一致、可复现，
不同条件下不会出现结果漂移。响应包含 `snapshotVersion`、使用的 `ldTaskId`、
条件以及入选位点的统计信息。

其他只读接口：`GET /api/snapshot`（版本与计数）、`GET /api/batches`（批次列表）。

---

## 5. 并发一致性

- 提交在全局提交锁内完成“校验 → 持久化 → CAS 切换快照”；
- 读操作开始时取一次 `AtomicReference<DataSnapshot>`，整个请求只使用该快照，
  看不到部分批次或部分位点；
- LD 计算以 `(version, window)` 去重，计算与导入互不干扰。

## 6. 错误响应

统一结构：`{ "status", "error", "message", "details" }`。
状态码：400 参数/格式错误、404 资源不存在、409 冲突或任务取消、
408 任务超时、422 校验失败（含定位明细）、500 内部错误。

## 7. 构建与测试

```bash
mvn test           # 运行全部自动化测试（49 个用例）
mvn spring-boot:run
mvn package
```

测试覆盖：基因型编码、TSV/内存解析与定位错误、重复导入幂等、跨批次增量与冲突、
缺失率/染色体/位置过滤、LD 数学正确性、重复与并发计算只执行一次、取消与超时、
崩溃恢复与 tmp 清理、并发导入/读取的快照隔离、筛选组合可复现性、HTTP 端到端。
