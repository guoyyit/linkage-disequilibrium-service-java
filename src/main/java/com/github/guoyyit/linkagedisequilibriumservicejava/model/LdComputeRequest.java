package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * Request to (ensure) an LD computation over committed data.
 *
 * @param taskId              optional client supplied idempotency key
 * @param windowBp            maximum physical distance between paired variants; 0 means unlimited
 * @param timeoutMillis       task timeout; &lt;=0 uses the server default
 */
public record LdComputeRequest(String taskId, long windowBp, long timeoutMillis) {

    public static LdComputeRequest defaults() {
        return new LdComputeRequest(null, 0, 0);
    }
}
