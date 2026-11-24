package com.skydispatch.repository;

import com.skydispatch.domain.entity.VertiportSite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 机巢站点仓储：低频变更，但容量/周转需要能被基础设施页单独修改。 */
@Repository
public interface VertiportSiteRepository extends JpaRepository<VertiportSite, Long> {

    Optional<VertiportSite> findBySiteKey(String siteKey);

    List<VertiportSite> findAllByOrderByIdAsc();

    boolean existsByCode(String code);
}
