package com.github.guoyyit.linkagedisequilibriumservicejava.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Location of the durable batch journal. */
@Component
@ConfigurationProperties(prefix = "ld-service.storage")
public class StorageProperties {

    /** Directory under which committed batch files are written. */
    private String dir = "data/batches";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }
}
