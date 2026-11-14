package com.skydispatch.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 遥测广播器：管理所有在线 WebSocket 连接，并把消息推给它们。
 *
 * <p>三个关键点：
 * <ol>
 *   <li><b>线程安全</b>：仿真引擎在调度线程里推送，连接又可能在 Tomcat 线程里建立/断开，
 *       所以用 {@link ConcurrentHashMap} 存会话。</li>
 *   <li><b>慢客户端隔离</b>：单个会话发送失败只移除它自己，绝不能影响其他连接，
 *       更不能把异常抛回仿真引擎（否则整个仿真会停）。</li>
 *   <li><b>序列化只做一次</b>：消息体序列化成字符串后广播给所有连接，
 *       而不是每个连接序列化一遍。</li>
 * </ol>
 */
@Component
public class TelemetryBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(TelemetryBroadcaster.class);

    private final ObjectMapper objectMapper;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public TelemetryBroadcaster(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void register(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WebSocket 客户端接入: id={}, 当前在线={}", session.getId(), sessions.size());
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session.getId());
        log.info("WebSocket 客户端断开: id={}, 当前在线={}", session.getId(), sessions.size());
    }

    public int onlineCount() {
        return sessions.size();
    }

    /**
     * 广播一条消息。任何异常都被吞掉并记录，保证调用方（仿真引擎）不受影响。
     */
    public void broadcast(WsMessage message) {
        if (sessions.isEmpty()) {
            return;
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(message);
        } catch (IOException e) {
            log.error("WebSocket 消息序列化失败: type={}", message.type(), e);
            return;
        }
        TextMessage textMessage = new TextMessage(json);
        sessions.values().forEach(session -> sendQuietly(session, textMessage));
    }

    private void sendQuietly(WebSocketSession session, TextMessage message) {
        if (!session.isOpen()) {
            sessions.remove(session.getId());
            return;
        }
        try {
            // WebSocketSession 不是线程安全的，这里加锁保证同一连接不会被并发写坏
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (Exception e) {
            log.warn("推送失败，移除该连接: id={}, 原因={}", session.getId(), e.getMessage());
            sessions.remove(session.getId());
            closeQuietly(session);
        }
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // 关闭失败无所谓，连接已经不可用了
        }
    }

    /** 供看板/测试使用的在线连接数。 */
    public Set<String> onlineSessionIds() {
        return sessions.keySet();
    }
}
