package com.github.guoyyit.linkagedisequilibriumservicejava.service;

/** 指定版本的快照不存在。 */
public class SnapshotNotFoundException extends RuntimeException {
    public SnapshotNotFoundException(long version) {
        super("快照版本不存在: " + version);
    }
}
