package com.dlab.domain.firewall.repository;

import com.dlab.domain.firewall.entity.ApproverType;
import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.FirewallStatus;
import com.dlab.domain.firewall.entity.ResolutionCase;
import com.dlab.domain.user.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface FirewallRequestRepository extends JpaRepository<FirewallRequest, Long> {

    Optional<FirewallRequest> findByStudentIdAndStatus(Long studentId, FirewallStatus status);

    List<FirewallRequest> findByBranchIdAndStatusOrderByRequestedAtAsc(Long branchId, FirewallStatus status);

    /**
     * 대기중일 때만 상태를 전이시키는 <b>원자적</b> 갱신.
     *
     * <p>학부모 승인과 담당선생님 승인이 같은 순간에 들어올 수 있으므로(CLAUDE.md §3 동시성),
     * 조회 후 저장하는 방식이 아니라 조건부 UPDATE 한 방으로 처리한다.
     * 반환값 0이면 다른 쪽이 먼저 처리한 것이므로 호출부는 APPROVAL_ALREADY_PROCESSED로 응답한다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE FirewallRequest f
               SET f.status         = :status,
                   f.resolvedAt     = :resolvedAt,
                   f.resolverAccount = :resolver,
                   f.resolverType   = :resolverType,
                   f.resolutionCase = :resolutionCase,
                   f.rejectReason   = :rejectReason,
                   f.updatedAt      = :resolvedAt
             WHERE f.id = :id
               AND f.status = com.dlab.domain.firewall.entity.FirewallStatus.PENDING
            """)
    int resolveIfPending(Long id,
                         FirewallStatus status,
                         Instant resolvedAt,
                         UserAccount resolver,
                         ApproverType resolverType,
                         ResolutionCase resolutionCase,
                         String rejectReason);
}
