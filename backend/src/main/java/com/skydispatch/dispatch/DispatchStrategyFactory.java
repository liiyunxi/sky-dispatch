package com.skydispatch.dispatch;

import com.skydispatch.common.BusinessException;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.enums.DispatchStrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 策略工厂：把 Spring 容器里所有的 {@link DispatchStrategy} 收集起来，按类型取用。
 *
 * <p>关键点是构造函数注入 {@code List<DispatchStrategy>}：Spring 会把所有实现类都塞进来。
 * 于是新增一个策略只要写一个 {@code @Component}，这里一行都不用改。
 * 这比 {@code switch (type)} 硬编码优雅得多，也不会漏改。
 */
@Component
public class DispatchStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(DispatchStrategyFactory.class);

    private final Map<DispatchStrategyType, DispatchStrategy> strategies = new EnumMap<>(DispatchStrategyType.class);
    private final SkyProperties properties;

    public DispatchStrategyFactory(List<DispatchStrategy> discovered, SkyProperties properties) {
        this.properties = properties;
        for (DispatchStrategy strategy : discovered) {
            strategies.put(strategy.type(), strategy);
        }
        log.info("已加载 {} 种派单策略: {}", strategies.size(), strategies.keySet());
    }

    /** 按类型取策略。 */
    public DispatchStrategy get(DispatchStrategyType type) {
        DispatchStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw BusinessException.invalidParam("不支持的派单策略: " + type);
        }
        return strategy;
    }

    /**
     * 按名字取策略（接口层用，因为 HTTP 参数是字符串）。
     * 传 null 或空值时使用配置里的默认策略。
     */
    public DispatchStrategy get(String name) {
        if (name == null || name.isBlank()) {
            return get(DispatchStrategyType.valueOf(properties.dispatch().defaultStrategy()));
        }
        try {
            return get(DispatchStrategyType.valueOf(name.trim().toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidParam(
                    "未知策略 " + name + "，可选值: " + strategies.keySet());
        }
    }

    /** 所有可用策略类型，用于接口文档与前端下拉框。 */
    public List<DispatchStrategyType> availableTypes() {
        return List.copyOf(strategies.keySet());
    }
}
