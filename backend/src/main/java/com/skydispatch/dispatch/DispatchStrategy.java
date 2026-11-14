package com.skydispatch.dispatch;

import com.skydispatch.domain.enums.DispatchStrategyType;

import java.util.Comparator;

/**
 * 派单策略接口 —— 本平台的"可插拔决策算法"。
 *
 * <p>设计要点：
 * <ol>
 *   <li><b>硬性条件不在这里判断</b>。载重够不够、电量够不够、距离在不在搜索半径内，
 *       这些是"及格线"，在 {@code DispatchService} 里统一过滤并记录淘汰原因。
 *       策略只负责在<b>已及格的候选</b>之间排序，职责单一。</li>
 *   <li>{@link #score(DroneCandidate)} 返回的是<b>单个主指标</b>，会写进
 *       {@code dispatch_decision} 表用于解释"为什么选它"。</li>
 *   <li>{@link #comparator()} 表达完整排序（含并列时的兜底规则）。
 *       因为主指标相同的两架飞机，用不同次指标排序会得到不同结果，
 *       把并列规则显式写出来，结果才可复现。</li>
 * </ol>
 *
 * <p>添加新策略只需实现本接口并加 {@code @Component}，工厂会自动收集，无需改任何已有代码
 * （开闭原则）。
 */
public interface DispatchStrategy {

    /** 策略类型，用于接口参数与落库。 */
    DispatchStrategyType type();

    /** 主指标，越小越优先。 */
    double score(DroneCandidate candidate);

    /** 完整排序规则，默认按主指标升序。 */
    default Comparator<DroneCandidate> comparator() {
        return Comparator.comparingDouble(this::score);
    }

    /** 中文说明，会写进日志和决策明细，方便初学者看懂每种策略的取舍。 */
    String description();
}
