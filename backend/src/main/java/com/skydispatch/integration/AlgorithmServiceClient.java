package com.skydispatch.integration;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.dto.algorithm.AlgorithmDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Python 算法服务的客户端（A* 路径规划 + 冲突检测）。
 *
 * <p>设计原则：<b>算法服务是可降级的依赖，不是单点。</b>
 * <ul>
 *   <li>{@link #plan} 失败时返回 {@link Optional#empty()}，由调用方决定是"退化成直线航路"
 *       还是"直接报错"。规划失败本身也可能是正常结果（无可行路径），所以用 {@code found} 字段
 *       表达，而不是异常。</li>
 *   <li>{@link #checkConflicts} 失败时只记日志并返回空，绝不能让仿真引擎因为算法服务抖动而停摆。</li>
 * </ul>
 *
 * <p>注意这里的边界：<b>Java 负责"什么时候算"，Python 负责"怎么算"</b>。
 * 客户端只做协议转换，不放任何业务判断。
 */
@Component
public class AlgorithmServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AlgorithmServiceClient.class);

    private static final String PATH_PLAN_ENDPOINT = "/api/v1/path/plan";
    private static final String CONFLICT_CHECK_ENDPOINT = "/api/v1/conflict/check";
    private static final String HEALTH_ENDPOINT = "/health";

    private final RestClient restClient;
    private final SkyProperties properties;

    public AlgorithmServiceClient(RestClient algorithmRestClient, SkyProperties properties) {
        this.restClient = algorithmRestClient;
        this.properties = properties;
    }

    /**
     * 请求路径规划。
     *
     * @return 规划结果；算法服务不可用或未启用时返回空 Optional
     */
    public Optional<AlgorithmDtos.PathPlanResult> plan(AlgorithmDtos.PlanPathRequest request) {
        if (!properties.algorithm().enabled()) {
            log.debug("算法服务已禁用（sky.algorithm.enabled=false），跳过远程规划");
            return Optional.empty();
        }
        long start = System.currentTimeMillis();
        try {
            AlgorithmDtos.PathPlanResult result = restClient.post()
                    .uri(PATH_PLAN_ENDPOINT)
                    .body(request)
                    .retrieve()
                    .body(AlgorithmDtos.PathPlanResult.class);
            log.debug("路径规划返回: found={}, 航路点={}, 耗时={}ms",
                    result != null && result.found(),
                    result == null || result.waypoints() == null ? 0 : result.waypoints().size(),
                    System.currentTimeMillis() - start);
            return Optional.ofNullable(result);
        } catch (RestClientException e) {
            log.warn("调用算法服务路径规划失败（{}）：{}", PATH_PLAN_ENDPOINT, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 请求冲突检测。这段代码会被仿真引擎每个 tick 调用，因此失败必须"静默降级"。
     */
    public Optional<AlgorithmDtos.ConflictCheckResult> checkConflicts(AlgorithmDtos.ConflictCheckRequest request) {
        if (!properties.algorithm().enabled()) {
            return Optional.empty();
        }
        try {
            AlgorithmDtos.ConflictCheckResult result = restClient.post()
                    .uri(CONFLICT_CHECK_ENDPOINT)
                    .body(request)
                    .retrieve()
                    .body(AlgorithmDtos.ConflictCheckResult.class);
            return Optional.ofNullable(result);
        } catch (RestClientException e) {
            log.warn("调用算法服务冲突检测失败（{}）：{}", CONFLICT_CHECK_ENDPOINT, e.getMessage());
            return Optional.empty();
        }
    }

    /** 探活，用于 /actuator/health 与运维排查。 */
    public Optional<AlgorithmDtos.HealthResult> health() {
        try {
            return Optional.ofNullable(restClient.get()
                    .uri(HEALTH_ENDPOINT)
                    .retrieve()
                    .body(AlgorithmDtos.HealthResult.class));
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    /** 算法服务是否被启用（关闭时走直线航路降级，属于预期状态而非故障）。 */
    public boolean enabled() {
        return properties.algorithm().enabled();
    }

    /** 算法服务当前是否可用。 */
    public boolean isAvailable() {
        return properties.algorithm().enabled() && health().isPresent();
    }

    /** 供健康检查展示的目标地址。 */
    public String baseUrl() {
        return properties.algorithm().baseUrl();
    }
}
