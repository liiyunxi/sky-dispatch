package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.dto.TaskDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

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
        int succeeded = 0;
        // 尝试次数上限：避免大量"注定失败"的订单把一次扫单拖成分钟级
        int maxAttempts = Math.max(limit * 5, limit);

        for (DeliveryOrder order : orders) {
            if (succeeded >= limit || results.size() >= maxAttempts) {
                break;
            }
            try {
                results.add(dispatchService.dispatchOrder(order.getId(), strategyName));
                succeeded++;
            } catch (BusinessException e) {
                // 业务失败（无可用机、无可行航路）不中断整批：一个送不到的订单不该饿死后面所有订单
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
}
