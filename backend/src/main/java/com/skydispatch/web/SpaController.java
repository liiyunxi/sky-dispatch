package com.skydispatch.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 生产部署时的前端路由回落。
 *
 * <p>Render 上采用「一个 Java 服务同时托管前端静态资源 + API + WebSocket」的部署方式：
 * Maven 构建阶段会把 {@code frontend/dist} 复制到 {@code classpath:/static/}。
 * 但 Vue Router 使用 history 模式，用户直接访问 {@code /orders} 时服务端必须回落到
 * {@code index.html}，否则会得到 404。
 *
 * <p>这里只列出当前前端真实存在的路由，不使用 {@code /**} 通配，
 * 避免把 {@code /api}、{@code /ws}、{@code /actuator} 等路径误吞。
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/orders", "/tasks", "/drones", "/airspace"})
    public String index() {
        return "forward:/index.html";
    }
}
