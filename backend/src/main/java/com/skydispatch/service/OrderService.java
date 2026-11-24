package com.skydispatch.service;

import com.skydispatch.common.BusinessException;
import com.skydispatch.domain.entity.DeliveryOrder;
import com.skydispatch.domain.enums.OrderStatus;
import com.skydispatch.domain.enums.Priority;
import com.skydispatch.dto.CommonDtos;
import com.skydispatch.dto.OrderDtos;
import com.skydispatch.repository.DeliveryOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;

/**
 * 订单服务：下单、查询、取消。
 *
 * <p>订单状态机（MVP）：
 * <pre>
 *   CREATED ──派单──▶ ASSIGNED ──起飞──▶ IN_FLIGHT ──送达──▶ DELIVERED
 *      │                 │                 │
 *      └──── 取消 ────────┴─────────────────┴──────▶ CANCELLED
 *                        └── 失败 ──▶ FAILED
 * </pre>
 * 状态迁移规则集中写在 {@link #requireTransitionAllowed} 里，避免各个接口各写一套。
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DeliveryOrderRepository orderRepository;
    private final SequenceService sequenceService;
    private final EventService eventService;

    public OrderService(DeliveryOrderRepository orderRepository, SequenceService sequenceService,
                        EventService eventService) {
        this.orderRepository = orderRepository;
        this.sequenceService = sequenceService;
        this.eventService = eventService;
    }

    /** 创建订单。 */
    @Transactional
    public OrderDtos.Response create(OrderDtos.CreateRequest request) {
        DeliveryOrder order = DeliveryOrder.builder()
                .orderNo(nextOrderNo())
                .customerName(request.customerName())
                .customerPhone(request.customerPhone())
                .cargoName(request.cargoName())
                .weightKg(request.weightKg())
                .priority(request.priority() == null ? Priority.NORMAL : request.priority())
                .status(OrderStatus.CREATED)
                .pickupAddress(request.pickup().address())
                .pickupLat(request.pickup().lat())
                .pickupLon(request.pickup().lon())
                .dropoffAddress(request.dropoff().address())
                .dropoffLat(request.dropoff().lat())
                .dropoffLon(request.dropoff().lon())
                .expectDeliveryAt(request.expectDeliveryAt())
                .remark(request.remark())
                .build();

        DeliveryOrder saved = orderRepository.save(order);
        // 记一条"下单"事件：链路追踪的时间线必须从下单开始，否则新订单看起来像凭空出现
        eventService.orderCreated(saved);
        log.info("创建订单: orderNo={}, 重量={}kg, 优先级={}", saved.getOrderNo(), saved.getWeightKg(), saved.getPriority());
        return OrderDtos.Response.from(saved);
    }

    /** 分页查询。 */
    @Transactional(readOnly = true)
    public CommonDtos.PageResponse<OrderDtos.Response> list(OrderStatus status, Priority priority, Pageable pageable) {
        return list(status, null, priority, pageable);
    }

    /**
     * 分页查询（支持按状态集合过滤）。
     *
     * <p>订单页的「流水线阶段」一次代表多个状态（例如待派单 = CREATED + PLANNING + QUEUED），
     * 用 statuses 比让前端发三次请求再合并更简单，也保证分页总数正确。
     * statuses 非空时优先于单个 status。
     */
    @Transactional(readOnly = true)
    public CommonDtos.PageResponse<OrderDtos.Response> list(OrderStatus status, List<OrderStatus> statuses,
                                                            Priority priority, Pageable pageable) {
        Page<DeliveryOrder> page;
        if (statuses != null && !statuses.isEmpty()) {
            page = priority != null
                    ? orderRepository.findByStatusInAndPriority(statuses, priority, pageable)
                    : orderRepository.findByStatusIn(statuses, pageable);
        } else if (status != null && priority != null) {
            page = orderRepository.findByStatusAndPriority(status, priority, pageable);
        } else if (status != null) {
            page = orderRepository.findByStatus(status, pageable);
        } else {
            page = orderRepository.findAll(pageable);
        }
        List<OrderDtos.Response> content = page.getContent().stream()
                .filter(o -> statuses != null && !statuses.isEmpty() ? statuses.contains(o.getStatus())
                        : priority == null || o.getPriority() == priority)
                .map(OrderDtos.Response::from)
                .toList();
        return CommonDtos.PageResponse.of(content, page);
    }

    /** 订单详情。 */
    @Transactional(readOnly = true)
    public OrderDtos.Response get(Long id) {
        return OrderDtos.Response.from(require(id));
    }

    /** 取消订单。 */
    @Transactional
    public OrderDtos.Response cancel(Long id) {
        // 取消与派单会竞争同一订单：先锁行，避免"刚派出去就被取消"或状态互相覆盖
        DeliveryOrder order = requireForUpdate(id);
        if (!List.of(OrderStatus.CREATED, OrderStatus.PLANNING, OrderStatus.QUEUED, OrderStatus.ASSIGNED)
                .contains(order.getStatus())) {
            throw BusinessException.illegalState("订单 " + order.getOrderNo() + " 当前状态 " + order.getStatus() + " 不能取消");
        }
        OrderStatus from = order.getStatus();
        order.setStatus(OrderStatus.CANCELLED);
        eventService.orderStatusChanged(order, from.name(), OrderStatus.CANCELLED.name());
        log.info("取消订单: {}", order.getOrderNo());
        return OrderDtos.Response.from(order);
    }

    /** 按 id 取实体（不存在抛 404）。 */
    @Transactional(readOnly = true)
    public DeliveryOrder require(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("订单 id=" + id));
    }

    /**
     * 按 id 取实体并加悲观写锁。
     *
     * <p>必须在调用方的事务内使用：派单和取消都通过它串行化同一订单的并发修改。
     * 不要给这个方法单独加 {@code readOnly = true}，否则锁会随只读事务立即释放。
     */
    public DeliveryOrder requireForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("订单 id=" + id));
    }

    /**
     * 找出所有"待派单"的订单，按「优先级高 → 创建时间早」排序。
     *
     * <p>为什么要显式排序：派单顺序直接决定用户体验。
     * 一个 URGENT 的急救订单如果排在 10 个 LOW 订单后面，系统就失去了意义。
     */
    @Transactional(readOnly = true)
    public List<DeliveryOrder> findDispatchable() {
        return orderRepository.findByStatusIn(List.of(OrderStatus.CREATED, OrderStatus.QUEUED, OrderStatus.PLANNING))
                .stream()
                .sorted(Comparator.comparingInt((DeliveryOrder o) -> -o.getPriority().weight())
                        .thenComparing(DeliveryOrder::getCreatedAt))
                .toList();
    }

    /** 更新订单状态（供调度和仿真引擎调用）。 */
    @Transactional
    public void markStatus(DeliveryOrder order, OrderStatus target) {
        requireTransitionAllowed(order.getStatus(), target);
        order.setStatus(target);
        // 送达时刻单独落库：准时率用它，而不是任务完成（含返航）的时间
        if (target == OrderStatus.DELIVERED && order.getDeliveredAt() == null) {
            order.setDeliveredAt(java.time.Instant.now());
        }
        orderRepository.save(order);
    }

    /**
     * 设置订单的执行无人机（传 null 表示解绑，例如任务中止后）。
     * 单独抽出来是因为"改状态"和"改关联"是两件事，混在一起容易出现半个状态。
     */
    @Transactional
    public void applyAssignment(DeliveryOrder order, Long droneId) {
        order.setAssignedDroneId(droneId);
        orderRepository.save(order);
    }

    /** 只取订单号，用于列表页避免加载整个实体。 */
    @Transactional(readOnly = true)
    public String findOrderNo(Long orderId) {
        if (orderId == null) {
            return null;
        }
        return orderRepository.findById(orderId).map(DeliveryOrder::getOrderNo).orElse(null);
    }

    /** 状态迁移校验。 */
    private void requireTransitionAllowed(OrderStatus from, OrderStatus to) {
        if (from == to) {
            return;
        }
        boolean allowed = switch (from) {
            case CREATED -> to == OrderStatus.PLANNING || to == OrderStatus.QUEUED
                    || to == OrderStatus.ASSIGNED || to == OrderStatus.CANCELLED || to == OrderStatus.FAILED;
            case PLANNING, QUEUED -> to == OrderStatus.ASSIGNED || to == OrderStatus.CANCELLED || to == OrderStatus.FAILED;
            case ASSIGNED -> to == OrderStatus.IN_FLIGHT || to == OrderStatus.QUEUED
                    || to == OrderStatus.CANCELLED || to == OrderStatus.FAILED;
            case IN_FLIGHT -> to == OrderStatus.DELIVERED || to == OrderStatus.FAILED;
            case DELIVERED, FAILED, CANCELLED -> false;   // 终态
        };
        if (!allowed) {
            throw BusinessException.illegalState("订单状态不允许从 " + from + " 变为 " + to);
        }
    }

    /**
     * 生成订单号：SO + 日期 + 数据库序列号，例如 SO20261113000123。
     *
     * <p>用数据库序列取号，并发下单不会撞号；序列可能因事务回滚留下空号，
     * 这是序列号的标准语义，不影响唯一性。
     */
    private String nextOrderNo() {
        LocalDate today = LocalDate.now();
        long seq = sequenceService.next();
        return "SO" + today.format(DAY) + String.format("%06d", seq);
    }
}
