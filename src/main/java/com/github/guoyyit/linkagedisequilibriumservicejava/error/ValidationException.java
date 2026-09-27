package com.github.guoyyit.linkagedisequilibriumservicejava.error;

import com.github.guoyyit.linkagedisequilibriumservicejava.model.ErrorLocation;

import java.util.List;

/**
 * Raised when an import batch fails validation. Carries locatable errors.
 */
public class ValidationException extends RuntimeException {

    private final transient List<ErrorLocation> errors;

    public ValidationException(List<ErrorLocation> errors) {
        super(errors.isEmpty() ? "validation failed" : errors.get(0).message());
        this.errors = List.copyOf(errors);
    }

    public List<ErrorLocation> errors() {
        return errors;
    }
}
