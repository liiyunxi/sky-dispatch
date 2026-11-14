package com.skydispatch.service;

import com.skydispatch.domain.entity.NumberSequence;
import com.skydispatch.repository.NumberSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 流水号服务：把数据库序列包装成一次简单的"取号"调用。
 *
 * <p>放在独立 Bean 里，保证 {@code @Transactional} 走代理；
 * 调用方（下单、派单）本身已在事务中时，取号会加入同一个事务。
 * 序列值由数据库分配，不存在"先查再算"的竞态。
 */
@Service
public class SequenceService {

    private final NumberSequenceRepository repository;

    public SequenceService(NumberSequenceRepository repository) {
        this.repository = repository;
    }

    /** 取下一个全局流水号（1, 2, 3...）。 */
    @Transactional
    public long next() {
        return repository.save(new NumberSequence()).getId();
    }
}
