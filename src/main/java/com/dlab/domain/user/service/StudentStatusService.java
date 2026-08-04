package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.EnrollmentStatusHistory;
import com.dlab.domain.user.entity.EnrollmentStatusTransition;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.EnrollmentStatusHistoryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 학생 상태 관리 (P1-04) — 재원/휴원/퇴원/제적/수료 전이와 후속처리.
 *
 * <p><b>상태 변경과 후속처리는 같은 트랜잭션이다</b>(실행가이드 명시). 반만 처리되면
 * "퇴원인데 반 배정이 살아 있는" 상태가 남고, 그건 화면에서 보이지 않아 아무도 못 고친다.
 *
 * <p>후속처리 목록은 {@link EnrollmentStatusFollowUp} 구현체를 주입받아 돌린다 —
 * 급식·좌석 도메인이 생기면 이 클래스를 수정하지 않고 빈만 추가하면 된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class StudentStatusService {

    private final StudentQueryService studentQueryService;
    private final EnrollmentStatusHistoryRepository historyRepository;
    private final List<EnrollmentStatusFollowUp> followUps;
    private final Clock clock;

    /**
     * 상태 전이.
     *
     * @param effectiveDate 효력 발생일. 없으면 오늘. 소급 처리가 실제로 있어
     *                      (지난달에 그만뒀는데 이번 달에 입력) 받아둔다 —
     *                      환불 일할계산(I-26)이 이 날짜를 쓴다
     * @param reason        변경 사유. 제적·퇴원은 분쟁 소지가 있어 사실상 필수지만,
     *                      휴원 복귀까지 강제하면 입력을 피하려 아무 값이나 넣게 된다
     */
    public StudentEnrollment changeStatus(AuthPrincipal me, Long enrollmentId,
                                          EnrollmentStatus to, LocalDate effectiveDate,
                                          String reason) {
        // 지점 확인이 여기 들어 있다
        StudentEnrollment enrollment = studentQueryService.getEnrollment(me, enrollmentId);
        EnrollmentStatus from = enrollment.getEnrollmentStatus();

        if (!EnrollmentStatusTransition.isAllowed(from, to)) {
            throw new BusinessException(ErrorCode.INVALID_ENROLLMENT_STATUS_TRANSITION);
        }

        LocalDate effective = effectiveDate == null ? LocalDate.now(clock) : effectiveDate;
        enrollment.changeStatus(to, effective);
        historyRepository.save(
                new EnrollmentStatusHistory(enrollment, from, to, effective, reason));

        EnrollmentStatusFollowUp.Change change =
                new EnrollmentStatusFollowUp.Change(enrollment, from, to, effective);
        followUps.forEach(followUp -> followUp.apply(change));

        return enrollment;
    }

    /** 이 등록 건의 상태 변경 이력(최신순). */
    @Transactional(readOnly = true)
    public List<EnrollmentStatusHistory> history(AuthPrincipal me, Long enrollmentId) {
        studentQueryService.getEnrollment(me, enrollmentId);
        return historyRepository.findByEnrollmentIdOrderByCreatedAtDesc(enrollmentId);
    }
}
