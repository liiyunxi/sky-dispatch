package com.skydispatch.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 业务流水号发号器。
 *
 * <p><b>为什么需要它</b>：订单号 / 任务号原先用"当日已有条数 + 1"生成，
 * 两个并发请求会同时算出同一个号，最后只能靠唯一索引报错兜底。
 * 这里改为写入一行由数据库序列 {@code seq_number} 发号的记录，
 * 取回自增 id 作为流水号：发号由数据库保证原子、唯一，并发不会撞号。
 *
 * <p>序列值由数据库分配，即使外层业务事务回滚，号也不会被重复使用（只会留空号），
 * 这是序列号的标准语义；空号对业务无影响，重复号才是事故。
 */
@Entity
@Table(name = "number_sequence")
@Getter
@NoArgsConstructor
public class NumberSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "number_seq")
    @SequenceGenerator(name = "number_seq", sequenceName = "seq_number", allocationSize = 1)
    private Long id;
}
