package com.skydispatch.repository;

import com.skydispatch.domain.entity.DispatchDecision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 调度决策仓储：服务于「派单可解释性 / 复盘分析」模块。
 *
 * <p>按 orderId 查全部分项即可还原"当时怎么算的"：
 * 按 rankNo 升序就是候选机排名，筛 selected=true 就是最终选择。
 */
@Repository
public interface DispatchDecisionRepository extends JpaRepository<DispatchDecision, Long> {

    /** 取某订单的全部分决策明细，按名次升序，完整还原评分排序过程。 */
    List<DispatchDecision> findByOrderIdOrderByRankNoAsc(Long orderId);

    /** 只取被选中的那条决策（重派场景下可能有多条历史记录）。 */
    List<DispatchDecision> findByOrderIdAndSelectedTrue(Long orderId);

    /**
     * 删除某订单的全部决策明细（订单删除时的清理动作）。
     *
     * <p>派生删除方法需要事务，这里显式加 {@code @Transactional}；
     * 注意本表刻意没有外键，所以不会随订单自动级联删除，必须由服务层显式调用。
     */
    @Transactional
    void deleteByOrderId(Long orderId);
}
