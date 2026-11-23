package com.skydispatch.common;

/**
 * 业务错误码。
 *
 * <p>约定：1xxx 是调用方的问题（参数/状态），2xxx 是外部依赖的问题，5xxx 是服务端自己的问题。
 * 前端只需要判断 code 所在区间就能决定提示方式。
 */
public enum ErrorCode {

    /** 参数校验失败，例如重量为负数、经纬度缺失 */
    INVALID_PARAM(1001, "参数校验失败"),
    /** 查询的对象不存在 */
    NOT_FOUND(1002, "资源不存在"),
    /** 当前状态不允许该操作，例如已完成的任务不能再中止 */
    ILLEGAL_STATE(1003, "当前状态不允许该操作"),
    /** 没有满足载重/电量/距离条件的空闲无人机 */
    NO_AVAILABLE_DRONE(1004, "没有可用的无人机"),
    /** 未登录、token 过期或账号密码错误 */
    UNAUTHORIZED(1005, "未登录或登录已过期"),
    /** Python 算法服务连不上或超时 */
    ALGORITHM_UNAVAILABLE(2001, "算法服务不可用"),
    /** 算法服务返回了结果，但规划失败（无可行路径） */
    PATH_PLAN_FAILED(2002, "路径规划失败"),
    /** 兜底 */
    INTERNAL_ERROR(5000, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
