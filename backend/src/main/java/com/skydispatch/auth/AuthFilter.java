package com.skydispatch.auth;

import com.skydispatch.config.SkyProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * API 鉴权过滤器。
 *
 * <p>只拦 {@code /api/**}：静态资源、SPA 路由、Swagger、Actuator 放行，
 * 保证登录页能打开、健康检查能被 Render 访问。
 *
 * <p>不走 Spring Security 是为了不引入额外依赖；如果后续需要角色/权限/方法级注解，
 * 再把本过滤器替换成 SecurityFilterChain 即可，前端协议（Bearer Token）不用变。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    /** 通过鉴权后写入 request 的用户名，Controller 可注入使用。 */
    public static final String USER_ATTRIBUTE = "sky.auth.user";

    private final SkyProperties properties;
    private final AuthService authService;

    public AuthFilter(SkyProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.auth().enabled()) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        // 非 API（静态前端、SPA 路由、actuator、swagger）不拦
        if (!path.startsWith("/api/")) {
            return true;
        }
        // 登录接口本身必须匿名可访问
        return path.startsWith("/api/v1/auth/login");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim()
                : null;
        String user = authService.verifyAndGetUser(token);
        if (user == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":1005,\"message\":\"未登录或登录已过期，请重新登录\",\"data\":null}");
            return;
        }
        request.setAttribute(USER_ATTRIBUTE, user);
        filterChain.doFilter(request, response);
    }
}
