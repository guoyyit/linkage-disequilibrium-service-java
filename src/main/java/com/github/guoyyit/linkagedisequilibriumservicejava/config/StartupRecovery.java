package com.github.guoyyit.linkagedisequilibriumservicejava.config;

import com.github.guoyyit.linkagedisequilibriumservicejava.service.storage.SnapshotStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Replays committed batches into memory on startup. */
@Component
public class StartupRecovery implements ApplicationRunner {

    private final SnapshotStore store;

    public StartupRecovery(SnapshotStore store) {
        this.store = store;
    }

    @Override
    public void run(ApplicationArguments args) {
        store.recover();
    }
}
