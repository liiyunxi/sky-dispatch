package com.skydispatch.service;

import com.skydispatch.dispatch.DispatchDecisionRow;
import com.skydispatch.domain.entity.DispatchDecision;
import com.skydispatch.repository.DispatchDecisionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 调度决策记账器，<b>独立事务提交</b>。
 *
 * <p>这个类解决一个很容易被忽略、但业务上很重要的矛盾：
 * <blockquote>
 *   "派单失败时最需要审计记录，可失败又会让事务回滚，
 *    于是审计记录恰好被一起回滚掉了。"
 * </blockquote>
 *
 * <p>典型场景：订单要送 99kg，没有任何机型扛得动。此时
 * {@code DispatchService} 会抛业务异常 → 事务回滚 → 如果决策记录写在同一个事务里，
 * 运营看到的就只有"没有任何可用无人机"这一句话，完全不知道系统评估过谁、为什么都不行。
 *
 * <p>解法是把记账放到 {@link Propagation#REQUIRES_NEW} 的新事务里：
 * 外层事务挂起，记账独立提交，外层再怎么回滚也影响不到它。
 *
 * <p>为什么必须是独立的 Bean：{@code @Transactional} 靠代理生效，
 * 同类内部自调用不会走代理，传播行为也不会生效。
 */
@Service
public class DispatchDecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(DispatchDecisionRecorder.class);

    private final DispatchDecisionRepository decisionRepository;

    public DispatchDecisionRecorder(DispatchDecisionRepository decisionRepository) {
        this.decisionRepository = decisionRepository;
    }

    /**
     * 覆盖式记录某订单的本次决策链（先清历史再写入，保证 {@code /dispatch/decisions}
     * 看到的是"最近一次决策"的完整快照，而不是多次尝试的混合）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long orderId, String strategy, List<DispatchDecisionRow> rows) {
        decisionRepository.deleteByOrderId(orderId);
        List<DispatchDecision> entities = new ArrayList<>(rows.size());
        for (DispatchDecisionRow row : rows) {
            entities.add(DispatchDecision.builder()
                    .orderId(orderId)
                    .strategy(strategy)
                    .droneId(row.droneId())
                    .score(row.score())
                    .rankNo(row.rankNo())
                    .selected(row.selected())
                    .rejectedReason(row.rejectedReason())
                    .detail(row.detail())
                    .build());
        }
        decisionRepository.saveAll(entities);
        log.debug("调度决策已记账: orderId={}, 候选 {} 条, 策略={}", orderId, rows.size(), strategy);
    }
}
