package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.dto.TaskDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批量派单编排。
 *
 * <p><b>为什么必须是独立的 Bean</b>（这是理解 Spring 事务的关键一课）：
 * {@code @Transactional} 靠动态代理实现。在同一个类里
 * {@code this.dispatchOrder(...)} 走的是原始对象，不经过代理，
 * 事务注解<b>完全失效</b>。失效的直接后果就是：
 * {@code DispatchService.filterCandidates} 里访问懒加载的 {@code drone.getModel()}
 * 会抛 {@code LazyInitializationException: Could not initialize proxy - no session}。
 *
 * <p>拆成独立 Bean 后，每次 {@code dispatchOrder} 调用都穿过代理，
 * 于是获得两个正确行为：
 * <ol>
 *   <li>每个订单一个独立事务——单个订单失败（比如被禁飞区完全阻断）只回滚它自己，
 *       不会毒化整批（若在同一事务里捕获异常继续，事务会被标记 rollback-only 而全线失败）；</li>
 *   <li>懒加载在事务内完成。</li>
 * </ol>
 *
 * <p>它同时也承担"扫单排序"的职责：按「优先级高 → 创建时间早」决定先派谁，
 * 这是保证 URGENT 急救订单不被 LOW 订单堵住的关键。
 */
@Service
public class DispatchBatchService {

    private static final Logger log = LoggerFactory.getLogger(DispatchBatchService.class);

    private final DispatchService dispatchService;
    private final OrderService orderService;

    public DispatchBatchService(DispatchService dispatchService, OrderService orderService) {
        this.dispatchService = dispatchService;
        this.orderService = orderService;
    }

    /**
     * 扫描待派订单并逐个派发。
     *
     * <p><b>limit 限制的是"成功派单数"，不是"尝试次数"</b>——这一点很关键。
     * 早期版本写成"最多尝试 limit 单就停"，结果出现了订单饥饿：
     * 按优先级排序后前 3 单里如果有 2 单因为续航不足必然失败，
     * 它们就会永久占满批次，后面所有能送的订单永远轮不到。
     * 现在改成"最多成功 limit 单"，尝试次数另设上限防止空转。
     *
     * @param strategyName 策略名，缺省用配置默认值
     * @param limit        本次最多成功派发多少单
     * @return 每个订单的尝试结果，含失败原因
     */
    public List<TaskDtos.DispatchResult> dispatchPending(String strategyName, int limit) {
        List<DeliveryOrder> orders = orderService.findDispatchable();
        List<TaskDtos.DispatchResult> results = new ArrayList<>();
        if (orders.isEmpty() || limit <= 0) {
            return results;
        }

        // 1) 同一份机队快照上，为这一批订单各取一遍排序后的候选（只读，不写库）
        // 批量视野控制在 3 倍配额：既能跨单比较候选，又不会让一次扫单做上百次可达性搜索
        int maxAttempts = Math.max(limit * 3, limit);
        Map<Long, List<Long>> rankedByOrder = new LinkedHashMap<>();
        Map<Long, DeliveryOrder> orderById = new LinkedHashMap<>();
        int attempts = 0;
        for (DeliveryOrder order : orders) {
            if (attempts++ >= maxAttempts) {
                break;
            }
            try {
                TaskDtos.CandidatePreview preview = dispatchService.previewCandidates(order.getId(), strategyName);
                List<Long> droneIds = preview.qualified().stream()
                        .map(TaskDtos.QualifiedDrone::droneId)
                        .toList();
                if (!droneIds.isEmpty()) {
                    rankedByOrder.put(order.getId(), droneIds);
                    orderById.put(order.getId(), order);
                }
            } catch (BusinessException e) {
                // 单条订单不可派（无候选/航路不可达）不阻断整批
                log.warn("订单 {} 批量评估失败: {}", order.getOrderNo(), e.getMessage());
            }
        }
        if (rankedByOrder.isEmpty()) {
            return results;
        }

        // 2) 批量分配：先按优先级顺序做"一机一单"的贪心，再做两两交换的局部优化，
        //    避免"前一单随手抢走最好的飞机、后一单只能拿剩下的"这种即时贪心的短视
        Map<Long, Long> assignment = new LinkedHashMap<>();
        Set<Long> usedDrones = new HashSet<>();
        for (Map.Entry<Long, List<Long>> entry : rankedByOrder.entrySet()) {
            for (Long droneId : entry.getValue()) {
                if (usedDrones.add(droneId)) {
                    assignment.put(entry.getKey(), droneId);
                    break;
                }
            }
        }
        improveAssignments(assignment, rankedByOrder);

        // 3) 逐个提交：每个订单仍是独立短事务，提交时用偏好无人机保留批量方案
        int succeeded = 0;
        for (Map.Entry<Long, Long> entry : assignment.entrySet()) {
            if (succeeded >= limit) {
                break;
            }
            DeliveryOrder order = orderById.get(entry.getKey());
            try {
                results.add(dispatchService.dispatchOrder(order.getId(), strategyName, entry.getValue()));
                succeeded++;
            } catch (BusinessException e) {
                log.warn("订单 {} 派单失败: {}", order.getOrderNo(), e.getMessage());
                results.add(new TaskDtos.DispatchResult(null, strategyName, false, 0L,
                        "订单 " + order.getOrderNo() + " 派单失败：" + e.getMessage()));
            }
        }
        if (succeeded == 0 && !results.isEmpty()) {
            log.warn("本轮扫单 {} 单全部失败（通常意味着机队电量不足、距离过远或航路被禁飞区阻断）",
                    results.size());
        }
        return results;
    }

    /**
     * 两两交换局部优化：如果 A 用 Y、B 用 X 能让两单的候选排名之和更小，就交换。
     *
     * <p>排名来自策略评分排序（0 = 最优）。只做 3 轮，规模是每批最多十几单，
     * 代价可以忽略，但能消除"最好飞机被次要订单占用"的典型贪心缺陷。
     */
    private void improveAssignments(Map<Long, Long> assignment, Map<Long, List<Long>> rankedByOrder) {
        List<Long> orderIds = new ArrayList<>(assignment.keySet());
        boolean changed = true;
        int passes = 0;
        while (changed && passes++ < 3) {
            changed = false;
            for (int i = 0; i < orderIds.size(); i++) {
                for (int j = i + 1; j < orderIds.size(); j++) {
                    Long oi = orderIds.get(i);
                    Long oj = orderIds.get(j);
                    Long di = assignment.get(oi);
                    Long dj = assignment.get(oj);
                    if (di == null || dj == null || di.equals(dj)) {
                        continue;
                    }
                    List<Long> ri = rankedByOrder.get(oi);
                    List<Long> rj = rankedByOrder.get(oj);
                    int current = ri.indexOf(di) + rj.indexOf(dj);
                    int swapped = ri.indexOf(dj) + rj.indexOf(di);
                    if (ri.contains(dj) && rj.contains(di) && swapped < current) {
                        assignment.put(oi, dj);
                        assignment.put(oj, di);
                        changed = true;
                    }
                }
            }
        }
    }
}
