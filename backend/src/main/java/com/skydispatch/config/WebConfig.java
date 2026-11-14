package com.skydispatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域（CORS）配置。
 *
 * <p>先说结论：<b>用 Vite 开发服务器时其实不需要它</b>。
 * `frontend/vite.config.ts` 把 `/api` 和 `/ws` 代理到了 8080，
 * 浏览器只跟 5173 打交道，属于同源请求，压根不会触发跨域检查。
 *
 * <p>那为什么还要写？因为下面两种场景绕不开：
 * <ol>
 *   <li>用 `pnpm preview`（4173 端口）或把 dist 挂到别的静态服务器上做验收，
 *       此时没有代理，就是真正的跨域；</li>
 *   <li>前端同学想直接 `fetch('http://localhost:8080/api/v1/...')` 调试，
 *       不想每次都起代理。</li>
 * </ol>
 *
 * <p>注意 WebSocket 的跨域不归这里管 —— 它在
 * {@link WebSocketConfig} 里用 {@code setAllowedOriginPatterns("*")} 单独配置。
 *
 * <p><b>安全提醒</b>：这里只允许本机来源。生产环境应该改成具体域名
 * （例如 `https://dispatch.example.com`），绝不要图省事写 `*`
 * —— 一旦开了 `allowCredentials` 又允许任意来源，等于把接口对所有网站开放。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 本地开发与预览允许的来源。 */
    private static final String[] LOCAL_ORIGINS = {
            "http://localhost:5173",
            "http://127.0.0.1:5173",
            "http://localhost:4173",
            "http://127.0.0.1:4173",
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(LOCAL_ORIGINS)
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")   // 导出 CSV 时前端要读文件名
                .allowCredentials(true)
                .maxAge(3600);                            // 预检结果缓存 1 小时，减少 OPTIONS 请求
    }
}
