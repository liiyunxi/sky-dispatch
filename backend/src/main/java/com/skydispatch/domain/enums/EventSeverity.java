package com.skydispatch.domain.enums;

/**
 * 事件日志级别。
 *
 * <p>取值刻意与常见日志框架（SLF4J）对齐，仿真引擎写 event_log 时可以顺手把同一级别
 * 也输出到控制台，便于联调时时间线对得上。
 */
public enum EventSeverity {

    /** 调试信息，生产环境可丢弃。 */
    DEBUG,

    /** 常规信息。 */
    INFO,

    /** 警告，可能影响后续流程。 */
    WARN,

    /** 错误，流程已失败。 */
    ERROR
}
