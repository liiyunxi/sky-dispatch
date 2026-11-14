package com.skydispatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * sky-dispatch 启动类。
 *
 * <p>它是整个 Java 后端的入口，只做三件事：
 * <ol>
 *   <li>{@code @SpringBootApplication}：开启自动配置与组件扫描（扫描 com.skydispatch 及子包）。</li>
 *   <li>{@code @EnableScheduling}：打开定时任务，仿真引擎靠它每秒推进一拍。</li>
 *   <li>{@code @ConfigurationPropertiesScan}：把带 {@code @ConfigurationProperties} 的配置类注册成 Bean。</li>
 * </ol>
 *
 * <p>启动后常用地址：
 * <ul>
 *   <li>Swagger UI：http://localhost:8080/swagger-ui.html</li>
 *   <li>健康检查：http://localhost:8080/actuator/health</li>
 *   <li>看板指标：http://localhost:8080/api/v1/dashboard/kpi</li>
 *   <li>遥测 WebSocket：ws://localhost:8080/ws/telemetry</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SkyDispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SkyDispatchApplication.class, args);
    }
}
