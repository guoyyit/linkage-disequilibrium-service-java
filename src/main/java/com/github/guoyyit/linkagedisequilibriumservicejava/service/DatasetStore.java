package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.DatasetSnapshot;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.GenotypeBatch;

import java.util.List;

/**
 * 已提交批次的不可变快照存储。
 *
 * <p>提交是原子的：并发导入串行化提交，读取只能看到完整快照；
 * 每个已提交批次持久化到本地文件（原子落盘），进程重启后可恢复。
 */
public interface DatasetStore {

    /** 当前最新快照；尚无数据时返回 null。 */
    DatasetSnapshot current();

    /** 按版本获取快照；不存在抛 {@link SnapshotNotFoundException}。 */
    DatasetSnapshot byVersion(long version);

    /** 批次是否已提交（用于重复导入幂等）。 */
    boolean containsBatch(String batchId);

    /**
     * 提交一个已通过校验的批次，生成新的不可变快照。
     * 重复 batchId 必须被忽略（不改变快照）。
     */
    DatasetSnapshot commit(GenotypeBatch batch);

    /** 启动时从持久化目录恢复已提交批次，重建最新快照。 */
    void recover();

    /** 所有历史版本快照。 */
    List<DatasetSnapshot> versions();
}
