package com.skydispatch.common;

import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各种异常翻译成统一的 {@link ApiResponse}。
 *
 * <p>好处是 Controller 里不用写 try-catch，业务代码只管抛异常，
 * 对外错误格式永远一致，前端也只需要一套解析逻辑。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：可预期，按 errorCode 映射 HTTP 状态。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.getErrorCode();
        HttpStatus status = switch (code) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ILLEGAL_STATE -> HttpStatus.CONFLICT;
            case ALGORITHM_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case UNAUTHORIZED -> HttpStatus.UNAUTHORIZED;
            default -> HttpStatus.BAD_REQUEST;
        };
        log.warn("业务异常 code={} message={}", code.getCode(), ex.getMessage());
        return ResponseEntity.status(status).body(ApiResponse.fail(code, ex.getMessage()));
    }

    /** @Valid 校验失败：把所有字段错误拼成一句话，方便定位。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(this::describeFieldError)
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.INVALID_PARAM, detail));
    }

    /** 路径变量/查询参数类型不对，例如 /orders/abc。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.INVALID_PARAM, "参数 " + ex.getName() + " 类型不正确"));
    }

    /** JPA 找不到实体。 */
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleEntityNotFound(EntityNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND, ex.getMessage()));
    }

    /** 调用 Python 算法服务时连接失败/超时。 */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ApiResponse<Void>> handleAlgorithmDown(ResourceAccessException ex) {
        log.error("算法服务调用失败: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(ErrorCode.ALGORITHM_UNAVAILABLE,
                        "无法连接算法服务，请确认 algorithm-service 已启动（默认 http://localhost:8000）"));
    }

    /** 兜底：未预料的异常，记录完整堆栈但要对外隐藏细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOthers(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR, ex.getClass().getSimpleName() + ": " + ex.getMessage()));
    }

    private String describeFieldError(FieldError error) {
        return error.getField() + " " + error.getDefaultMessage();
    }
}
