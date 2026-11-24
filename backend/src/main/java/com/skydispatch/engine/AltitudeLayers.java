package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.Drone;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 巡航高度层分配。
 *
 * <p>没有分层时，全机队的航路点高度都是 {@code sky.flight.default-altitude-m}，
 * 冲突检测里的"高度差 ≥ 垂直间隔"这条规则永远不会生效——所有飞机在同一层，
 * 只能靠水平绕飞解决冲突。分层后：
 * <ul>
 *   <li>不同层的飞机在水平接近时天然解耦，不会产生无意义的避让；</li>
 *   <li>地图/机队表能看到飞机在不同高度飞行，而不是贴在同一条线上；</li>
 *   <li>爬升/下降的垂直动力学有了明确目标高度。</li>
 * </ul>
 *
 * <p><b>分配规则</b>：按无人机 id 轮转高度层。同一架飞机在整个任务（去程 + 返程）里
 * 保持同一层，避免中途换层带来的额外爬升能耗与新的穿越冲突。
 * 这里用 id 而不是随机数，保证同一支机队每次启动的分配结果一致（可复现）。
 */
@Component
public class AltitudeLayers {

    private final SkyProperties properties;

    public AltitudeLayers(SkyProperties properties) {
        this.properties = properties;
    }

    /**
     * 该无人机的巡航高度层（米）。
     *
     * @param drone 无人机；id 为空时退化为按编号哈希，保证仍然稳定
     */
    public double forDrone(Drone drone) {
        List<Double> layers = properties.flight().altitudeLayersM();
        if (layers == null || layers.isEmpty()) {
            return properties.flight().defaultAltitudeM();
        }
        long key = drone.getId() != null
                ? drone.getId()
                : Math.abs(String.valueOf(drone.getCode()).hashCode());
        // 从 0 开始轮转：编号 1 的飞机落在最低层，便于演示与排查
        int index = (int) Math.floorMod(key - 1, layers.size());
        return layers.get(index);
    }

    /** 配置里配置的高度层（只读），供校验与测试使用。 */
    public List<Double> layers() {
        List<Double> layers = properties.flight().altitudeLayersM();
        return layers == null ? List.of() : List.copyOf(layers);
    }
}
