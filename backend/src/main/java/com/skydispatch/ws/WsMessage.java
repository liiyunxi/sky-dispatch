package com.skydispatch.ws;

import java.time.Instant;

/**
 * WebSocket 下行消息的信封。
 *
 * <p>协议刻意做得很朴素：一条文本帧就是一个 JSON 对象，字段固定为
 * {@code type / ts / payload}。好处是前端用最原始的
 * {@code new WebSocket(url)} + {@code JSON.parse(event.data)} 就能开发，
 * 不需要 STOMP 协议栈（新手最容易卡在这里）。
 *
 * @param type    消息类型，取值见 {@link Type}
 * @param ts      服务端时间
 * @param payload 具体数据，由 type 决定结构
 */
public record WsMessage(String type, Instant ts, Object payload) {

    /** 消息类型常量。前端按这个字段做 switch 分发。 */
    public static final class Type {
        /** 客户端刚连上时推送的全量快照 */
        public static final String SNAPSHOT = "SNAPSHOT";
        /** 每个仿真 tick 的位置/电量更新 */
        public static final String TELEMETRY = "TELEMETRY";
        /** 业务事件（下单、派单、完成、异常） */
        public static final String EVENT = "EVENT";
        /** 新发现的冲突 */
        public static final String CONFLICT = "CONFLICT";
        /** 汇总指标，频率低于 TELEMETRY */
        public static final String KPI = "KPI";
        /** 心跳响应 */
        public static final String PONG = "PONG";

        private Type() {
        }
    }

    public static WsMessage of(String type, Object payload) {
        return new WsMessage(type, Instant.now(), payload);
    }

    public static WsMessage telemetry(Object payload) {
        return of(Type.TELEMETRY, payload);
    }

    public static WsMessage event(Object payload) {
        return of(Type.EVENT, payload);
    }

    public static WsMessage conflict(Object payload) {
        return of(Type.CONFLICT, payload);
    }

    public static WsMessage kpi(Object payload) {
        return of(Type.KPI, payload);
    }

    public static WsMessage snapshot(Object payload) {
        return of(Type.SNAPSHOT, payload);
    }

    public static WsMessage pong() {
        return of(Type.PONG, null);
    }
}
