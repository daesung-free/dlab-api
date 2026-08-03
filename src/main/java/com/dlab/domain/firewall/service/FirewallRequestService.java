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
    private final ApprovalService approvalService;

    @Transactional
    public FirewallRequest create(Long enrollmentId, int requestedMinutes, String reason) {
        if (requestedMinutes <= 0 || requestedMinutes > FirewallRequest.MAX_REQUESTED_MINUTES) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "해제 시간은 1~%d분 사이여야 합니다.".formatted(FirewallRequest.MAX_REQUESTED_MINUTES));
        }

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));

        ApprovalRequest approval = approvalService.create(enrollment, RequestType.FIREWALL_UNLOCK);

        return firewallRequestRepository.save(new FirewallRequest(
                enrollment.getAcademy(), enrollment.getYear(), enrollment,
                approval, (short) requestedMinutes, reason));
    }
}
