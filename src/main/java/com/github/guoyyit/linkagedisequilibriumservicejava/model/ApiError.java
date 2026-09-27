package com.github.guoyyit.linkagedisequilibriumservicejava.model;

import java.util.List;

/**
 * Uniform error body. {@code details} carries locatable validation errors when present.
 */
public record ApiError(int status, String error, String message, List<ErrorLocation> details) {
}
