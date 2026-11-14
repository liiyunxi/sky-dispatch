package com.skydispatch.common;

/**
 * 业务异常：Service 层遇到"可预期的错误"时抛它，由 {@link GlobalExceptionHandler} 统一转成 JSON。
 *
 * <p>为什么继承 RuntimeException：Spring 的 {@code @Transactional} 默认只在运行时异常时回滚，
 * 用受检异常会导致"抛了异常但事务没回滚"的隐蔽 bug。
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage());
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** 资源不存在。 */
    public static BusinessException notFound(String what) {
        return new BusinessException(ErrorCode.NOT_FOUND, what + " 不存在");
    }

    /** 状态机不允许的操作。 */
    public static BusinessException illegalState(String message) {
        return new BusinessException(ErrorCode.ILLEGAL_STATE, message);
    }

    /** 参数不合法。 */
    public static BusinessException invalidParam(String message) {
        return new BusinessException(ErrorCode.INVALID_PARAM, message);
    }
}
