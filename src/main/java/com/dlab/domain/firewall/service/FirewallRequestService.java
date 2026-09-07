package com.dlab.domain.firewall.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.firewall.entity.FirewallRequest;
import com.dlab.domain.firewall.repository.FirewallRequestRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.service.AppScopeResolver;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 와이파이 해제 신청.
 *
 * <p>승인 흐름(타임아웃·에스컬레이션·3케이스·동시성)은 전부 {@link ApprovalService}가 담당한다.
 * 여기는 해제 자체의 고유 정보만 다룬다.
 */
@Service
@RequiredArgsConstructor
public class FirewallRequestService {

    private final FirewallRequestRepository firewallRequestRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final AppScopeResolver scopeResolver;
    private final ApprovalService approvalService;
    private final FirewallAdminService firewallAdminService;
    private final java.time.Clock clock;

    /**
     * 로그인한 학생 본인의 신청.
     *
     * <p>등록 건을 요청 값으로 받지 않고 <b>토큰의 계정에서 찾아낸다</b> —
     * 클라이언트가 지정하게 하면 남의 등록 건으로 신청할 수 있다.
     */
    @Transactional
    public FirewallRequest createForAccount(Long accountId, int requestedMinutes, String reason) {
        return createForAccount(accountId, requestedMinutes, reason, null, null);
    }

    /**
     * 구간 지정 신청 — "15:00~17:00".
     *
     * <p>구간을 안 보내면 종전대로 <b>승인 즉시 시작</b>이다. 앱 구버전과 이미 들어온
     * 신청이 그대로 돌아야 한다.
     */
    @Transactional
    public FirewallRequest createForAccount(Long accountId, int requestedMinutes, String reason,
                                            Instant startAt, Instant endAt) {
        StudentEnrollment enrollment = scopeResolver.requireStudent(accountId, "방화벽 해제 신청");
        return create(enrollment.getId(), requestedMinutes, reason, startAt, endAt);
    }

    @Transactional
    public FirewallRequest create(Long enrollmentId, int requestedMinutes, String reason) {
        return create(enrollmentId, requestedMinutes, reason, null, null);
    }

    @Transactional
    public FirewallRequest create(Long enrollmentId, int requestedMinutes, String reason,
                                  Instant startAt, Instant endAt) {
        if ((startAt == null) != (endAt == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "해제 시작·종료 시각은 함께 보내야 합니다.");
        }
        if (startAt != null) {
            if (!endAt.isAfter(startAt)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "종료 시각이 시작 시각보다 빠릅니다.");
            }
            // 지난 시각으로 신청하면 승인되자마자 이미 끝난 구간이 된다
            if (startAt.isBefore(Instant.now(clock))) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "지난 시각으로는 신청할 수 없습니다.");
            }
            requestedMinutes = (int) java.time.Duration.between(startAt, endAt).toMinutes();
        }
        if (requestedMinutes <= 0 || requestedMinutes > FirewallRequest.MAX_REQUESTED_MINUTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "해제 시간은 1~%d분 사이여야 합니다.".formatted(FirewallRequest.MAX_REQUESTED_MINUTES));
        }

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));

        // ★ 제재를 승인 큐보다 먼저 본다. 막힌 학생의 신청이 큐까지 올라가면
        //   학부모가 승인했는데 거절되는 상황이 생긴다
        firewallAdminService.activeRestriction(enrollmentId).ifPresent(restriction -> {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "위반 적발로 해제 신청이 제한된 상태입니다.");
        });

        ApprovalRequest approval = approvalService.create(enrollment, RequestType.FIREWALL_UNLOCK);

        FirewallRequest request = firewallRequestRepository.save(new FirewallRequest(
                enrollment.getAcademy(), enrollment, approval, (short) requestedMinutes, reason));
        if (startAt != null) {
            request.requestWindow(startAt, endAt);
        }
        return request;
    }
}
