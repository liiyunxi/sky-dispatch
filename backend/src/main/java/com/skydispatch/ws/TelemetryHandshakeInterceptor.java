package com.skydispatch.ws;

import com.skydispatch.auth.AuthFilter;
import com.skydispatch.auth.AuthService;
import com.skydispatch.config.SkyProperties;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * WebSocket 握手鉴权。
 *
 * <p>浏览器 WebSocket API 不能自定义请求头，所以 token 通过查询参数传递：
 * {@code ws://host/ws/telemetry?token=...}。握手阶段校验失败直接拒绝，
 * 不会建立连接，也不会把遥测数据推给未登录客户端。
 */
public class TelemetryHandshakeInterceptor implements HandshakeInterceptor {

    private final SkyProperties properties;
    private final AuthService authService;

    public TelemetryHandshakeInterceptor(SkyProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!properties.auth().enabled()) {
            return true;
        }
        String token = tokenFromQuery(request.getURI().getQuery());
        String user = authService.verifyAndGetUser(token);
        if (user == null) {
            return false;
        }
        attributes.put(AuthFilter.USER_ATTRIBUTE, user);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 无需处理
    }

    private static String tokenFromQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("token=")) {
                return URLDecoder.decode(part.substring("token=".length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
