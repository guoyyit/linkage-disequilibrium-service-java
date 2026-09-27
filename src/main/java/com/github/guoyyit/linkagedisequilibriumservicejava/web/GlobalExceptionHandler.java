package com.github.guoyyit.linkagedisequilibriumservicejava.web;

import com.github.guoyyit.linkagedisequilibriumservicejava.dto.ErrorResponse;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.BatchRejectedException;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.JobNotFoundException;
import com.github.guoyyit.linkagedisequilibriumservicejava.service.SnapshotNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * 统一错误响应。批次被拒绝使用 422 并返回全部可定位原因，绝不静默忽略；
 * 快照/任务不存在使用 404；参数非法使用 400。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BatchRejectedException.class)
    public ResponseEntity<ErrorResponse> handleRejected(BatchRejectedException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ErrorResponse("BATCH_REJECTED", ex.batchId(), ex.errors()));
    }

    @ExceptionHandler({SnapshotNotFoundException.class, JobNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("NOT_FOUND", null, List.of(ex.getMessage())));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_REQUEST", null, List.of(ex.getMessage())));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("MALFORMED_JSON", null, List.of("请求体不是合法 JSON")));
    }
}
