package com.github.guoyyit.linkagedisequilibriumservicejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Defaults for long running tasks. */
@Component
@ConfigurationProperties(prefix = "ld-service.task")
public class TaskProperties {

    private long defaultTimeoutMillis = 300_000L;

    public long getDefaultTimeoutMillis() {
        return defaultTimeoutMillis;
    }

    public void setDefaultTimeoutMillis(long defaultTimeoutMillis) {
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }
}
