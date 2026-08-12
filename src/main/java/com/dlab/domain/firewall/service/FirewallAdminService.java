package com.dlab.domain.firewall.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.entity.FirewallRestriction;
import com.dlab.domain.firewall.entity.FirewallViolation;
import com.dlab.domain.firewall.entity.UnlockStatus;
import com.dlab.domain.firewall.repository.FirewallRequestRepository;
import com.dlab.domain.firewall.repository.FirewallRestrictionRepository;
import com.dlab.domain.firewall.repository.FirewallViolationRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.integration.zyxel.NebulaClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 방화벽 해제 관리 (F-4.11-10).
 *
 * <p>신청은 {@link FirewallRequestService}, 승인은 {@code ApprovalService}가 한다.
 * 여기는 <b>관리자가 보고 손대는 것</b>만 담당한다 — 이력·해제중 목록·위반 적발·제재.
 *
 * <h2>★ Nebula 실제 제어는 아직 목업이다 (E-1)</h2>
 * 제어 단위(단말 vs 정책)가 미확정이라 {@link NebulaClient} 인터페이스만 두고 로그 구현체가
 * 붙어 있다. <b>상태는 정확히 바뀌지만 와이파이는 실제로 열리고 닫히지 않는다.</b>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FirewallAdminService {

    /** 제재 발동 기준. "2회 적발 시 2주 제한"(요구사항 F-4.11-10). */
    private static final int VIOLATION_THRESHOLD = 2;

    /** 제재 기간. */
    private static final Duration RESTRICTION_PERIOD = Duration.ofDays(14);

    private final FirewallRequestRepository requestRepository;
    private final FirewallViolationRepository violationRepository;
    private final FirewallRestrictionRepository restrictionRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final NebulaClient nebulaClient;
    private final Clock clock;

    // ── 조회 ──────────────────────────────────────────────────

    /** 신청·해제 이력. 조건을 비우면 그 조건은 빠진다. */
    public List<FirewallRequest> search(AuthPrincipal me, Long academyId, Long enrollmentId,
                                        UnlockStatus status, LocalDate from, LocalDate to) {
        Long scope = resolveScope(me, academyId);
        return requestRepository.search(scope, enrollmentId, status,
                from == null ? null : from.atStartOfDay(com.dlab.common.config.TimeConfig.KST).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(com.dlab.common.config.TimeConfig.KST).toInstant());
    }

    /** 현재 해제중. 종료가 임박한 순이다. */
    public List<FirewallRequest> active(AuthPrincipal me, Long academyId) {
        return requestRepository.findActive(resolveScope(me, academyId));
    }

    public List<FirewallViolation> violations(Long enrollmentId) {
        return violationRepository
                .findByEnrollmentIdAndDeletedFalseOrderByOccurredAtDesc(enrollmentId);
    }

    // ── 위반·제재 ─────────────────────────────────────────────

    /**
     * 위반 적발 등록. 2회가 되면 그 자리에서 2주 제한이 걸린다.
     *
     * <p><b>해제 신청과 연결되지 않아도 된다</b>({@code firewallRequestId}가 {@code null}) —
     * 아예 신청 없이 뚫어 쓴 경우가 적발 대상의 대부분이다.
     *
     * <p><b>해제중이면 즉시 차단한다.</b> 적발해놓고 남은 시간 동안 계속 쓰게 두면
     * 적발의 의미가 없다.
     */
    @Transactional
    public FirewallViolation recordViolation(AuthPrincipal me, Long enrollmentId,
                                             Long firewallRequestId) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        FirewallRequest request = firewallRequestId == null ? null
                : requestRepository.findById(firewallRequestId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                                "해제 신청을 찾을 수 없습니다."));

        Instant now = Instant.now(clock);
        FirewallViolation violation = violationRepository.save(
                new FirewallViolation(enrollment, request, now));

        if (request != null && request.isActive()) {
            block(request);
        }

        int count = violationRepository
                .findByEnrollmentIdAndDeletedFalseOrderByOccurredAtDesc(enrollmentId).size();
        if (count >= VIOLATION_THRESHOLD
                && restrictionRepository.findActive(enrollmentId, now).isEmpty()) {
            restrictionRepository.save(new FirewallRestriction(
                    enrollment, now, now.plus(RESTRICTION_PERIOD), count));
        }
        return violation;
    }

    /**
     * 지금 신청이 막혀 있나.
     *
     * <p>신청 경로가 이 값을 먼저 본다 — 막힌 학생의 신청이 승인 큐까지 올라가면
     * 학부모가 승인했는데 거절되는 상황이 생긴다.
     */
    public java.util.Optional<FirewallRestriction> activeRestriction(Long enrollmentId) {
        return restrictionRepository.findActive(enrollmentId, Instant.now(clock));
    }

    /** 제재 해제(착오 등록 정정). 남겨두면 근거가 사라지므로 soft delete다. */
    @Transactional
    public void liftRestriction(AuthPrincipal me, Long restrictionId) {
        FirewallRestriction restriction = restrictionRepository.findById(restrictionId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "제한 이력을 찾을 수 없습니다."));

        if (!me.canAccessAcademy(restriction.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        restriction.markDeleted();
    }

    // ── 만료 ──────────────────────────────────────────────────

    /**
     * 만료된 해제를 닫는다. 스케줄러가 부른다.
     *
     * <p><b>차단을 먼저 보내고 상태를 바꾼다.</b> 반대로 하면 차단이 실패했는데 만료로
     * 기록돼 <b>영원히 열린 채로 남는다</b> — 다음 배치가 다시 잡지 못한다.
     *
     * @return 닫은 건수
     */
    @Transactional
    public int expireOverdue() {
        List<FirewallRequest> overdue = requestRepository.findExpired(Instant.now(clock));
        for (FirewallRequest request : overdue) {
            block(request);
        }
        return overdue.size();
    }

    private void block(FirewallRequest request) {
        nebulaClient.block(request.getZyxelSiteId(),
                String.valueOf(request.getEnrollment().getId()));
        request.expire();
    }

    // ─────────────────────────────────────────────────────────

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));

        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /** 지점 스코프는 서버가 강제한다 — 요청 값을 그대로 믿으면 남의 지점이 새어나간다. */
    private Long resolveScope(AuthPrincipal me, Long requested) {
        if (me.allAcademy()) {
            if (requested == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해 주세요.");
            }
            return requested;
        }
        if (requested != null && !me.canAccessAcademy(requested)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        Long own = me.academyScopeFilter();
        if (own == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해 주세요.");
        }
        return own;
    }
}
