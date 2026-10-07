package com.dlab.domain.firewall.repository;

import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.UnlockStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FirewallRequestRepository extends JpaRepository<FirewallRequest, Long> {

    Optional<FirewallRequest> findByApprovalRequestId(Long approvalRequestId);

    /**
     * 관리자 목록 — 지점·기간·학생·상태 필터.
     *
     * <p>조건이 비면 그 조건은 빠진다({@code :param IS NULL}) — 화면이 필터를 하나씩
     * 켜고 끄기 때문에 조합마다 메서드를 두면 감당이 안 된다.
     */
    @Query("""
            SELECT r FROM FirewallRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            JOIN FETCH r.approvalRequest
            WHERE r.academy.id = :academyId
              AND (:enrollmentId IS NULL OR e.id = :enrollmentId)
              AND (:status IS NULL OR r.unlockStatus = :status)
              AND (CAST(:from AS timestamp) IS NULL OR r.createdAt >= :from)
              AND (CAST(:to AS timestamp) IS NULL OR r.createdAt < :to)
              AND r.deleted = false
            ORDER BY r.id DESC
            """)
    List<FirewallRequest> search(@Param("academyId") Long academyId,
                                 @Param("enrollmentId") Long enrollmentId,
                                 @Param("status") UnlockStatus status,
                                 @Param("from") Instant from,
                                 @Param("to") Instant to);

    /** 현재 해제중. 관리자 화면이 가장 자주 여는 조회다. */
    @Query("""
            SELECT r FROM FirewallRequest r
            JOIN FETCH r.enrollment e
            JOIN FETCH e.student
            WHERE r.academy.id = :academyId
              AND r.unlockStatus = com.dlab.domain.firewall.entity.UnlockStatus.ACTIVE
              AND r.deleted = false
            ORDER BY r.unlockEndAt
            """)
    List<FirewallRequest> findActive(@Param("academyId") Long academyId);

    /**
     * 해제 활성 스케줄러가 훑는 경로 — <b>승인됐는데 아직 열지 않은 것</b>.
     *
     * <p>지정 구간이 있으면 <b>그 시각이 와야</b> 연다. "15시에 열어달라"를 승인 즉시 열면
     * 학생이 적어낸 시간대와 어긋난다. 구간을 안 적었으면 승인되는 대로 연다.
     */
    @Query("""
            SELECT r FROM FirewallRequest r
            JOIN FETCH r.approvalRequest a
            JOIN FETCH r.enrollment e
            WHERE r.unlockStatus = com.dlab.domain.firewall.entity.UnlockStatus.WAITING
              AND a.status = com.dlab.domain.approval.entity.ApprovalStatus.APPROVED
              AND (r.requestedStartAt IS NULL OR r.requestedStartAt <= :at)
              AND (r.requestedEndAt IS NULL OR r.requestedEndAt > :at)
              AND r.deleted = false
            ORDER BY r.id
            """)
    List<FirewallRequest> findApprovedWaiting(@Param("at") Instant at);

    /**
     * 앱 이력 조회 — 학생 본인·학부모가 보는 목록.
     *
     * <p>관리자 목록({@link #search})과 달리 <b>지점 조건이 없다.</b> 등록 건으로 이미
     * 한 사람에 묶여 있어서, 지점을 또 거는 것은 중복이고 학부모 경로에서는 지점이 없다.
     */
    @Query("""
            SELECT r FROM FirewallRequest r
            JOIN FETCH r.approvalRequest
            WHERE r.enrollment.id = :enrollmentId
              AND r.createdAt >= :from
              AND r.createdAt < :to
              AND r.deleted = false
            ORDER BY r.id DESC
            """)
    List<FirewallRequest> findByEnrollmentAndPeriod(@Param("enrollmentId") Long enrollmentId,
                                                    @Param("from") Instant from,
                                                    @Param("to") Instant to);

    /** 만료 스케줄러가 훑는 경로 — 해제중인데 종료 시각이 지난 것. */
    @Query("""
            SELECT r FROM FirewallRequest r
            WHERE r.unlockStatus = com.dlab.domain.firewall.entity.UnlockStatus.ACTIVE
              AND r.unlockEndAt <= :at
              AND r.deleted = false
            """)
    List<FirewallRequest> findExpired(@Param("at") Instant at);
}
