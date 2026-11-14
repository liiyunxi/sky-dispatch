package com.skydispatch.common;

import java.time.Instant;

/**
 * 统一响应包装。
 *
 * <p>为什么要包一层？因为前端/调用方需要一个稳定的判断依据。
 * 只看 HTTP 状态码不够（有些网关会改状态码），所以固定用 {@code code == 0} 表示成功。
 *
 * @param code      业务码，0 = 成功，其余见 {@link ErrorCode}
 * @param message   提示信息，成功时为 "ok"
 * @param data      业务数据，可以是对象、数组或 null
 * @param timestamp 服务端时间，便于排查时序问题
 */
public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data, Instant.now());
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.getCode(),
                message == null ? errorCode.getMessage() : message,
                null, Instant.now());
    }
}
