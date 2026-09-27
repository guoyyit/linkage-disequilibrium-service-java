package com.github.guoyyit.linkagedisequilibriumservicejava.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 进程启动后从本地持久化目录恢复已提交批次，重建快照，使任务可继续推进。
 */
@Component
public class StartupRecovery {

    private final DatasetStore store;

    public StartupRecovery(DatasetStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        store.recover();
    }
}
