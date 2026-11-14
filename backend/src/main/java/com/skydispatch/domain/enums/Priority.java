package com.skydispatch.domain.enums;

/**
 * 订单优先级。
 *
 * <p>枚举带权重，是为了让"优先级"在代码里可计算：派单排序时用 {@link #weight()}
 * 做数值比较，而不是到处写 if-else 判断枚举名。
 */
public enum Priority {

    /** 低优先级，可延迟派送。 */
    LOW(0),

    /** 普通优先级，默认值。 */
    NORMAL(1),

    /** 高优先级，尽量插队。 */
    HIGH(2),

    /** 紧急，必须优先保障。 */
    URGENT(3);

    /** 数值权重，越大越优先。 */
    private final int weight;

    Priority(int weight) {
        this.weight = weight;
    }

    /**
     * 返回优先级数值权重：LOW=0、NORMAL=1、HIGH=2、URGENT=3。
     *
     * <p>调度排序用它做升/降序比较，避免在业务代码里散落 switch 分支。
     */
    public int weight() {
        return weight;
    }
}
