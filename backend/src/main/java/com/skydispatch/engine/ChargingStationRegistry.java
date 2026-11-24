package com.skydispatch.engine;

import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.ChargingStation;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 充电站运行时资源：桩位占用、排队与充电曲线。
 *
 * <p>站点台账里的 {@code chargeSlots} 是物理桩位数，但"现在有几架在充、谁在排队"
 * 属于运行态，不能写进静态台账。这里用内存维护：
 * <ul>
 *   <li><b>容量</b>：同时在充的无人机数不超过桩位数，超出的进入 FIFO 队列；</li>
 *   <li><b>排队</b>：队列按到达顺序推进，空出桩位自动叫号；</li>
 *   <li><b>充电曲线</b>：低 SOC 恒流快充，超过 taper 阈值后功率线性衰减，
 *       避免"最后 20% 和前面一样快"的失真。</li>
 * </ul>
 *
 * <p>状态以 droneId 为键、站点为值，任务完成/失败/离开站点时必须 {@link #release}，
 * 否则桩位会被永久占住（这是排队系统最典型的泄漏）。
 */
@Component
public class ChargingStationRegistry {

    private final SkyProperties properties;

    /** stationId → 正在充电的 droneId 集合。 */
    private final Map<Long, Set<Long>> charging = new ConcurrentHashMap<>();
    /** stationId → 等待队列（FIFO）。 */
    private final Map<Long, Deque<Long>> queues = new ConcurrentHashMap<>();
    /** droneId → 所在站点（充电或排队）。 */
    private final Map<Long, Long> stationOfDrone = new ConcurrentHashMap<>();
    /** 正在排队的 droneId。 */
    private final Set<Long> waiting = ConcurrentHashMap.newKeySet();

    public ChargingStationRegistry(SkyProperties properties) {
        this.properties = properties;
    }

    /** 站点桩位数（至少 1，避免配置为 0 导致永远充不上）。 */
    public int capacity(ChargingStation station) {
        return station == null || station.getChargeSlots() == null
                ? 1 : Math.max(1, station.getChargeSlots());
    }

    /**
     * 尝试占用一个桩位。
     *
     * @return true 表示已开始充电；false 表示桩位已满，调用方应入队
     */
    public boolean tryAcquire(long stationId, long droneId, int capacity) {
        Set<Long> slots = charging.computeIfAbsent(stationId, k -> ConcurrentHashMap.newKeySet());
        if (slots.contains(droneId)) {
            waiting.remove(droneId);
            stationOfDrone.put(droneId, stationId);
            return true;
        }
        if (slots.size() < capacity) {
            slots.add(droneId);
            waiting.remove(droneId);
            stationOfDrone.put(droneId, stationId);
            return true;
        }
        return false;
    }

    /** 加入站点等待队列（同一架飞机不会重复入队）。 */
    public void enqueue(long stationId, long droneId) {
        stationOfDrone.put(droneId, stationId);
        if (waiting.add(droneId)) {
            queues.computeIfAbsent(stationId, k -> new ConcurrentLinkedDeque<>()).addLast(droneId);
        }
    }

    /**
     * 低电量优先插队：电量接近安全底线的飞机排到队首。
     *
     * <p>否则"先到先得"会让低电飞机在队尾干等——虽然排队不再耗电，
     * 但长航程任务会被拖到失去意义，插队是真实充电运营里的常见规则。
     */
    public void enqueueFirst(long stationId, long droneId) {
        stationOfDrone.put(droneId, stationId);
        if (waiting.add(droneId)) {
            queues.computeIfAbsent(stationId, k -> new ConcurrentLinkedDeque<>()).addFirst(droneId);
        }
    }

    /**
     * 叫号：有空桩时把队首飞机转入充电。
     *
     * @return 本拍新获得桩位的飞机数
     */
    public int promote(long stationId, int capacity) {
        Set<Long> slots = charging.computeIfAbsent(stationId, k -> ConcurrentHashMap.newKeySet());
        Deque<Long> queue = queues.get(stationId);
        if (queue == null) {
            return 0;
        }
        int promoted = 0;
        while (slots.size() < capacity) {
            Long next = queue.pollFirst();
            if (next == null) {
                break;
            }
            waiting.remove(next);
            slots.add(next);
            stationOfDrone.put(next, stationId);
            promoted++;
        }
        return promoted;
    }

    /** 是否正在排队（排队期间不充电，但仍在站点上空/等待位占用时间）。 */
    public boolean isQueued(long droneId) {
        return waiting.contains(droneId);
    }

    /** 是否正在充电。 */
    public boolean isCharging(long droneId) {
        Long stationId = stationOfDrone.get(droneId);
        return stationId != null && charging
                .getOrDefault(stationId, Set.of())
                .contains(droneId);
    }

    /** 释放飞机占用的桩位/队列位置（任务完成、失败、离开站点时调用）。 */
    public void release(long droneId) {
        waiting.remove(droneId);
        Long stationId = stationOfDrone.remove(droneId);
        if (stationId == null) {
            return;
        }
        Set<Long> slots = charging.get(stationId);
        if (slots != null) {
            slots.remove(droneId);
        }
        Deque<Long> queue = queues.get(stationId);
        if (queue != null) {
            queue.remove(droneId);
        }
    }

    /** 站点当前占用桩位数。 */
    public int occupancy(long stationId) {
        return charging.getOrDefault(stationId, Set.of()).size();
    }

    /** 站点当前排队长度。 */
    public int queueLength(long stationId) {
        Deque<Long> queue = queues.get(stationId);
        return queue == null ? 0 : queue.size();
    }

    /** 新建/重置运行时清空：桩位占用与排队都属于本次演练的运行态。 */
    public void clearAll() {
        charging.clear();
        queues.clear();
        stationOfDrone.clear();
        waiting.clear();
    }

    /** 排队位置（1 = 队首）；不在队列返回 0。 */
    public int queuePosition(long droneId) {
        Long stationId = stationOfDrone.get(droneId);
        if (stationId == null || !waiting.contains(droneId)) {
            return 0;
        }
        Deque<Long> queue = queues.get(stationId);
        if (queue == null) {
            return 0;
        }
        int position = 0;
        for (Long id : queue) {
            position++;
            if (id != null && id == droneId) {
                return position;
            }
        }
        return 0;
    }

    /**
     * 充电功率系数：SOC 低于 taper 起点时为 1，之后线性衰减到末端系数。
     *
     * @param socPct 当前电量百分比（0~100）
     */
    public double powerFactor(double socPct) {
        SkyProperties.Charging cfg = properties.charging();
        double start = Math.max(0.0, Math.min(99.0, cfg.taperStartPct()));
        double end = Math.max(0.05, Math.min(1.0, cfg.taperEndFactor()));
        if (socPct <= start) {
            return 1.0;
        }
        double t = Math.max(0.0, Math.min(1.0, (socPct - start) / (100.0 - start)));
        return 1.0 - (1.0 - end) * t;
    }
}
