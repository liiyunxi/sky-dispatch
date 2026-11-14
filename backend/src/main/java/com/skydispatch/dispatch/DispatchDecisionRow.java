package com.skydispatch.dispatch;

import java.math.BigDecimal;

/**
 * 一条调度决策记录（落库前的中间表示）。
 *
 * <p>为什么不直接传实体？因为决策的"生成"和"提交"需要分开：
 * 生成发生在派单业务的事务里，提交可能要用一个<b>独立事务</b>
 * （见 {@link com.skydispatch.service.DispatchDecisionRecorder}）。
 * 用一个不可变的中间对象传递，职责边界最清楚。
 *
 * @param droneId        候选无人机 id
 * @param droneCode      机身编号（便于人工阅读，不必再查表）
 * @param modelCode      机型编码
 * @param score          策略打分，越小越优先；被硬条件淘汰时为 null
 * @param rankNo         通过硬条件后的排名；被淘汰时为 null
 * @param selected       是否为最终选中者
 * @param rejectedReason 淘汰原因；通过硬条件时为 null
 * @param detail         分项指标 JSON，供复盘
 */
public record DispatchDecisionRow(
        Long droneId,
        String droneCode,
        String modelCode,
        BigDecimal score,
        Integer rankNo,
        boolean selected,
        String rejectedReason,
        String detail
) {
}
