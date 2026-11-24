package com.skydispatch.dto;

import java.time.Instant;
import java.util.List;

/** 演示场景编排的请求/响应模型。 */
public final class ScenarioDtos {

    private ScenarioDtos() {
    }

    /** 可一键触发的演示剧本。 */
    public enum Key {
        PEAK_DEMAND,
        FAULT_STORM,
        TEMP_NO_FLY,
        CHARGING_SURGE
    }

    /** 剧本目录（前端直接渲染成卡片）。 */
    public record Info(
            Key key,
            String title,
            String description,
            String impact,
            String risk
    ) {
    }

    /** 触发结果：做了什么、影响哪些对象、当时仿真时间。 */
    public record Result(
            Key key,
            String title,
            String message,
            List<String> details,
            Double simTimeS,
            Instant ts
    ) {
    }
}
