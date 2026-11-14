package com.skydispatch.engine;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 任务"改航航段"的内存标记。
 *
 * <p>仿真推进默认把航路理解为"去程折线 + 反向返航"两段结构（见 {@link SimulationTickService}），
 * 而自动改航是从飞机<b>当前位置</b>重画一条到当前目标的折线，不再符合这个结构：
 * <ul>
 *   <li>改航发生在返航段：新折线方向是"当前位置→机巢"，不能再做反向；</li>
 *   <li>改航发生在去程段：送达后回程必须重建成"送货点→机巢"直线，而不是沿避让折线原路返回。</li>
 * </ul>
 *
 * <p>用一个任务级内存标记告诉仿真推进器这两种情形即可，无需为此改动数据库表结构
 * （改航是瞬态运行事件，任务结束即清除）。
 */
@Component
public class RerouteRegistry {

    public enum Leg {
        /** 改航发生在去程（目标是送货点），送达后需重建回家直线。 */
        OUTBOUND,
        /** 改航发生在返航段（目标是机巢），沿新折线正向飞完即可。 */
        RETURNING
    }

    private final Map<Long, Leg> reroutedTasks = new ConcurrentHashMap<>();

    public void mark(Long taskId, Leg leg) {
        reroutedTasks.put(taskId, leg);
    }

    public Leg leg(Long taskId) {
        return reroutedTasks.get(taskId);
    }

    public boolean isRerouted(Long taskId) {
        return reroutedTasks.containsKey(taskId);
    }

    public void clear(Long taskId) {
        reroutedTasks.remove(taskId);
    }
}
