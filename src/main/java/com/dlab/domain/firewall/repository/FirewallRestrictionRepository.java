package com.dlab.domain.firewall.repository;

import com.dlab.domain.firewall.entity.FirewallRestriction;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FirewallRestrictionRepository extends JpaRepository<FirewallRestriction, Long> {

    /** 지금 걸려 있는 제재. 신청 시점에 이걸 먼저 본다. */
    @Query("""
            SELECT r FROM FirewallRestriction r
            WHERE r.enrollment.id = :enrollmentId
              AND r.restrictedFrom <= :at AND r.restrictedUntil > :at
              AND r.deleted = false
            ORDER BY r.restrictedUntil DESC
            LIMIT 1
            """)
    Optional<FirewallRestriction> findActive(@Param("enrollmentId") Long enrollmentId,
                                             @Param("at") Instant at);
}
