package com.skydispatch.repository;

import com.skydispatch.domain.entity.NumberSequence;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 业务流水号仓储：只负责向数据库序列取号，不承载业务查询。
 */
@Repository
public interface NumberSequenceRepository extends JpaRepository<NumberSequence, Long> {
}
