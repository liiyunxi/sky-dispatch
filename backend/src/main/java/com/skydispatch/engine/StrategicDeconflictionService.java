package com.skydispatch.engine;

import com.skydispatch.common.GeoPoint;
import com.skydispatch.common.GeoUtils;
import com.skydispatch.config.SkyProperties;
import com.skydispatch.domain.entity.DispatchTask;
import com.skydispatch.domain.entity.Drone;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 战略层去冲突：起飞时隙预约。
 *
 * <p><b>为什么需要战略层</b>：只有战术层（飞起来后预测冲突再改航）时，
 * 同一时刻从同一片区域起飞的飞机会在空中才被发现冲突，然后被迫绕飞，
 * 既费电又增加风险。真实低空管理会先在<b>地面</b>做流量管理：
 * 按"走廊扇区 + 高度层"分配起飞时隙，超出的架次顺延到下一个时隙。
 *
 * <p>这里用确定性的轻量模型：航路首末方位角确定走廊扇区，高度层由机型高度层给定，
 * 每个 (扇区, 高度层) 每 {@code slotSeconds} 允许 {@code departuresPerSlot} 架起飞。
 * 时隙分配结果缓存到任务上，不会每拍重复预约。
 */
@Component
public class StrategicDeconflictionService {

    private final SkyProperties properties;

    /** taskId → 已预约的起飞时刻（仿真秒）。 */
    private final Map<Long, Double> releaseAtByTask = new ConcurrentHashMap<>();
    /** taskId → 预约键，释放时用于回收名额。 */
    private final Map<Long, String> slotKeyByTask = new ConcurrentHashMap<>();
    /** 预约键 → 已占用该时隙的任务。 */
    private final Map<String, List<Long>> bookings = new ConcurrentHashMap<>();
    /** taskId → 占用者信息，供只读时隙视图展示"谁占了"。 */
    private final Map<Long, SlotOccupant> occupants = new ConcurrentHashMap<>();

    public StrategicDeconflictionService(SkyProperties properties) {
        this.properties = properties;
    }

    /** 时隙占用者。 */
    public record SlotOccupant(Long taskId, String taskNo, String droneCode, Long orderId) {
    }

    /** 一条"走廊扇区 + 高度层"的时隙。 */
    public record SlotView(
            String key,
            String sector,
            int layerM,
            double startSimSec,
            double endSimSec,
            int capacity,
            List<SlotOccupant> occupants
    ) {
    }

    /** 战略时隙快照（只读查询用）。 */
    public record Snapshot(
            boolean enabled,
            double slotSeconds,
            int departuresPerSlot,
            List<SlotView> slots
    ) {
    }

    /**
     * 为任务预约起飞时隙。
     *
     * @param path   本段航路（用首末点计算走廊扇区）
     * @param layerM 巡航高度层
     * @return 允许起飞的最早仿真时刻
     */
    public double requestSlot(DispatchTask task, Drone drone, List<GeoPoint> path,
                              double layerM, double simTimeS) {
        Double cached = releaseAtByTask.get(task.getId());
        if (cached != null) {
            return cached;
        }
        SkyProperties.Strategic cfg = properties.strategic();
        if (!cfg.enabled() || path == null || path.size() < 2) {
            releaseAtByTask.put(task.getId(), simTimeS);
            return simTimeS;
        }
        double slot = Math.max(1.0, cfg.slotSeconds());
        int perSlot = Math.max(1, cfg.departuresPerSlot());
        String baseKey = sectorOf(path) + "@" + Math.round(layerM);

        double candidate = Math.ceil(simTimeS / slot) * slot;
        for (int attempt = 0; attempt < 48; attempt++) {
            String key = baseKey + "#" + Math.round(candidate);
            List<Long> booked = bookings.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>());
            if (booked.size() < perSlot) {
                booked.add(task.getId());
                slotKeyByTask.put(task.getId(), key);
                releaseAtByTask.put(task.getId(), candidate);
                occupants.put(task.getId(), new SlotOccupant(
                        task.getId(), task.getTaskNo(), drone.getCode(), task.getOrderId()));
                return candidate;
            }
            candidate += slot;
        }
        // 极端的繁忙场景：兜底放行到 48 个时隙之后，避免任务永久卡死
        releaseAtByTask.put(task.getId(), candidate);
        return candidate;
    }

    /** 已预约的起飞时刻；没有预约返回仿真时刻本身。 */
    public double releaseAt(Long taskId, double simTimeS) {
        return releaseAtByTask.getOrDefault(taskId, simTimeS);
    }

    /** 释放任务时隙（完成/失败/中止时调用）。 */
    public void release(Long taskId) {
        releaseAtByTask.remove(taskId);
        occupants.remove(taskId);
        String key = slotKeyByTask.remove(taskId);
        if (key != null) {
            List<Long> booked = bookings.get(key);
            if (booked != null) {
                booked.remove(taskId);
                if (booked.isEmpty()) {
                    bookings.remove(key);
                }
            }
        }
    }

    /**
     * 只读快照：当前所有被占用的"走廊扇区 + 高度层 + 时隙"，以及占用者。
     *
     * <p>预约键形如 {@code 31.2,121.5:S3@80#12345}：{@code @} 前是扇区，
     * {@code @} 与 {@code #} 之间是高度层，{@code #} 后是时隙起始仿真秒。
     * 解析失败的行直接跳过——时隙视图是观测功能，不能因为脏数据把整页打挂。
     */
    public Snapshot snapshot() {
        SkyProperties.Strategic cfg = properties.strategic();
        double slotSeconds = Math.max(1.0, cfg.slotSeconds());
        int capacity = Math.max(1, cfg.departuresPerSlot());
        List<SlotView> slots = new ArrayList<>();
        bookings.forEach((key, taskIds) -> {
            SlotView view = parseSlot(key, slotSeconds, capacity);
            if (view == null) {
                return;
            }
            List<SlotOccupant> list = new ArrayList<>();
            for (Long taskId : taskIds) {
                SlotOccupant occupant = occupants.get(taskId);
                if (occupant != null) {
                    list.add(occupant);
                }
            }
            slots.add(new SlotView(view.key(), view.sector(), view.layerM(), view.startSimSec(),
                    view.endSimSec(), view.capacity(), list));
        });
        slots.sort(Comparator.comparingDouble(SlotView::startSimSec)
                .thenComparing(SlotView::sector)
                .thenComparingInt(SlotView::layerM));
        return new Snapshot(cfg.enabled(), slotSeconds, capacity, slots);
    }

    /** 新建/重置运行时清空全部预约与占用者。 */
    public void clearAll() {
        releaseAtByTask.clear();
        slotKeyByTask.clear();
        bookings.clear();
        occupants.clear();
    }

    private SlotView parseSlot(String key, double slotSeconds, int capacity) {
        int at = key.indexOf('@');
        int hash = at < 0 ? -1 : key.indexOf('#', at + 1);
        if (at <= 0 || hash <= at + 1) {
            return null;
        }
        try {
            String sector = key.substring(0, at);
            int layer = Integer.parseInt(key.substring(at + 1, hash));
            double start = Double.parseDouble(key.substring(hash + 1));
            return new SlotView(key, sector, layer, start, start + slotSeconds, capacity, List.of());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 走廊键：城市区域 + 方位扇区（跨城市的同扇区不应争抢同一时隙）。 */
    private String sectorOf(List<GeoPoint> path) {
        GeoPoint start = path.get(0);
        GeoPoint end = path.get(path.size() - 1);
        double bearing = GeoUtils.bearingDegrees(start.lat(), start.lon(), end.lat(), end.lon());
        int sector = (int) Math.floor(((bearing + 22.5) % 360.0) / 45.0);
        // 0.5° 网格 ≈ 55km，足以把四个演示城市分开
        String region = String.format("%.1f,%.1f",
                Math.round(start.lat() * 2) / 2.0,
                Math.round(start.lon() * 2) / 2.0);
        return region + ":S" + sector;
    }
}
