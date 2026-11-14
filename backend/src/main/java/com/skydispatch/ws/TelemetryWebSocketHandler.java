package com.skydispatch.ws;

import com.skydispatch.service.DashboardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 遥测 WebSocket 端点处理器，挂在 {@code /ws/telemetry}。
 *
 * <p>交互流程：
 * <pre>
 *   客户端连接
 *     └─ 服务端立刻回一条 SNAPSHOT（当前所有无人机位置 + KPI），避免首屏空白
 *   客户端发 {"type":"PING"}
 *     └─ 服务端回 {"type":"PONG"}
 *   仿真引擎每 tick
 *     └─ 服务端广播 TELEMETRY / EVENT / CONFLICT / KPI
 * </pre>
 *
 * <p>这里用 {@link ConcurrentWebSocketSessionDecorator} 包一层：它限制了
 * 单连接的发送缓冲上限（超过就断开慢客户端），防止某个卡住的浏览器把服务端内存吃光。
 */
@Component
public class TelemetryWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryWebSocketHandler.class);

    /** 单次发送超时 5 秒，缓冲上限 512KB，超过就认为客户端太慢，直接放弃该连接。 */
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT_BYTES = 512 * 1024;

    private final TelemetryBroadcaster broadcaster;
    private final DashboardService dashboardService;

    public TelemetryWebSocketHandler(TelemetryBroadcaster broadcaster, DashboardService dashboardService) {
        this.broadcaster = broadcaster;
        this.dashboardService = dashboardService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession safeSession = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT_BYTES);
        broadcaster.register(safeSession);
        // 首帧快照：新客户端一接入就能看到完整态势，而不是等下一个 tick
        try {
            broadcaster.broadcast(WsMessage.snapshot(dashboardService.buildSnapshot()));
        } catch (Exception e) {
            log.warn("推送初始快照失败: {}", e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        // 控制帧只支持 PING，其它内容忽略（遥测是单向推送，不需要客户端上报）
        if (payload != null && payload.contains("PING")) {
            try {
                session.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
            } catch (Exception e) {
                log.debug("回复 PONG 失败: {}", e.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        broadcaster.unregister(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("WebSocket 传输异常 id={}: {}", session.getId(), exception.getMessage());
        broadcaster.unregister(session);
    }
}
