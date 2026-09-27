package com.github.guoyyit.linkagedisequilibriumservicejava.web;

import com.github.guoyyit.linkagedisequilibriumservicejava.error.ConflictException;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.NotFoundException;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.TaskCancelledException;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.TaskTimeoutException;
import com.github.guoyyit.linkagedisequilibriumservicejava.error.ValidationException;
import com.github.guoyyit.linkagedisequilibriumservicejava.model.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.List;

/** Translates domain failures into stable HTTP error responses. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ApiError> validation(ValidationException ex) {
        return build(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed",
                ex.getMessage(), ex.errors());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex) {
        return build(HttpStatus.CONFLICT, "conflict", ex.getMessage(), List.of());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "not_found", ex.getMessage(), List.of());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MaxUploadSizeExceededException.class})
    public ResponseEntity<ApiError> badRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "bad_request", ex.getMessage(), List.of());
    }

    @ExceptionHandler(TaskTimeoutException.class)
    public ResponseEntity<ApiError> timeout(TaskTimeoutException ex) {
        return build(HttpStatus.REQUEST_TIMEOUT, "task_timeout", ex.getMessage(), List.of());
    }

    @ExceptionHandler(TaskCancelledException.class)
    public ResponseEntity<ApiError> cancelled(TaskCancelledException ex) {
        return build(HttpStatus.CONFLICT, "task_cancelled", ex.getMessage(), List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                ex.getClass().getSimpleName() + ": " + ex.getMessage(), List.of());
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String error, String message,
                                           List<com.github.guoyyit.linkagedisequilibriumservicejava.model.ErrorLocation> details) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), error, message, details));
    }
}
