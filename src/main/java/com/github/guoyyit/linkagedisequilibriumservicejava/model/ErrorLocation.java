package com.github.guoyyit.linkagedisequilibriumservicejava.model;

/**
 * Locatable validation error. Batch/row/column are 1-based when applicable.
 */
public record ErrorLocation(String batchId, Integer row, Integer column, String sample,
                            Variant variant, String code, String message) {

    public static ErrorLocation of(String batchId, String code, String message) {
        return new ErrorLocation(batchId, null, null, null, null, code, message);
    }
}
