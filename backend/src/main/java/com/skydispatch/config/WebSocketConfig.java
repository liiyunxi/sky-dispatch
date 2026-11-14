package com.skydispatch.config;

import com.skydispatch.ws.TelemetryWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 注册原生 WebSocket 端点。
 *
 * <p>为什么不用 STOMP + SockJS？因为本项目只有"服务端单向推送态势"这一种需求，
 * 原生 WebSocket 足够，且浏览器端零依赖、易调试（浏览器控制台里就能收发）。
 *
 * <p>{@code setAllowedOriginPatterns("*")} 是为了让本地打开的测试页面
 * （file:// 或别的端口）也能连上，生产环境要收紧成具体域名。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TelemetryWebSocketHandler telemetryHandler;

    public WebSocketConfig(TelemetryWebSocketHandler telemetryHandler) {
        this.telemetryHandler = telemetryHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(telemetryHandler, "/ws/telemetry")
                .setAllowedOriginPatterns("*");
    }
}
