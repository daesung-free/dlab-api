package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.master.entity.LockerMaster;
import com.dlab.domain.master.repository.LockerMasterRepository;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentStatusLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 학생 상태 관리 (요구사항 F-4.1-8).
 *
 * <p>재원 → 휴원 → 재원 / 퇴원 / 제적 / 수료.
 *
 * <p><b>★ 상태만 바꾸고 끝나면 안 된다.</b> 시트가 요구하는 후속처리를
 * <b>같은 트랜잭션에</b> 묶는다 — 상태는 퇴원인데 좌석은 그대로 잡혀 있는 상태가 생기면
 * 다음 학생이 그 자리를 못 받고, 원인을 찾기도 어렵다.
 *
 * <p><b>휴원은 정리 대상이 아니다.</b> 돌아올 학생의 좌석·사물함을 비우면 복귀 때 다시
 * 배정해야 하고 그 사이 다른 학생이 들어가 자리를 잃는다.
 *
 * <p><b>도메인별 후속처리는 {@link EnrollmentStatusFollowUp} 빈으로 확장한다.</b>
 * 여기에 직접 박으면 새 도메인을 만드는 사람이 이 파일을 알아채야만 추가되고,
 * 못 알아채면 조용히 빠진다 — 실제로 급식이 그랬다. 반·좌석·사물함·계정처럼
 * <b>어느 도메인에도 속하지 않는 정리</b>만 이 클래스가 직접 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentStatusService {

    private final StudentEnrollmentRepository enrollmentRepository;
    private final StudentStatusLogRepository statusLogRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final LockerMasterRepository lockerRepository;
    private final AccountRepository accountRepository;
    private final List<EnrollmentStatusFollowUp> followUps;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<StudentStatusLog> history(Long enrollmentId, AuthPrincipal principal) {
        StudentEnrollment enrollment = load(enrollmentId, principal);
        return statusLogRepository.findByEnrollmentIdAndDeletedFalseOrderByChangedAtDesc(
                enrollment.getId());
    }

    /**
     * 상태 전이 + 후속처리.
     *
     * @param reason 사유. 제적처럼 다툼이 생길 수 있는 전이는 사유가 남아야 한다
     */
    @Transactional
    public StatusChangeResult changeStatus(Long enrollmentId, EnrollmentStatus to, String reason,
                                           AuthPrincipal principal) {
        StudentEnrollment enrollment = load(enrollmentId, principal);
        EnrollmentStatus from = enrollment.getEnrollmentStatus();

        if (from == to) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 같은 상태입니다.");
        }
        // ★ 종료(퇴원·제적·수료)에서는 어디로도 나가지 못한다.
        //
        //   원래는 "재원으로 되돌리기"만 막았는데, 그러면 퇴원 → 휴원 → 재원으로 우회됐다.
        //   막아둔 규칙이 두 번에 나눠 부르면 통과하는 상태였다 — 화면에 없을 뿐 API 로는
        //   뚫린다.
        //
        //   종료는 후속처리(반 배정 해제·앱 계정 비활성·좌석 회수)가 이미 돈 상태다.
        //   되돌리려면 그것들을 되살려야 하는데, 무엇이 어떤 값이었는지는 남겨두지 않는다.
        //   그래서 착오 정정이든 재입학이든 **재등록(새 등록 행)**으로 처리한다.
        if (from.requiresCleanup()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "%s 상태에서는 상태를 바꿀 수 없습니다. 재등록으로 처리하세요.".formatted(from));
        }

        Instant now = Instant.now(clock);
        enrollment.updateEnrollment(null, null, to);
        statusLogRepository.save(new StudentStatusLog(enrollment, from, to, reason, now));

        List<EnrollmentStatusFollowUp.Note> notes = List.of();
        if (to.requiresCleanup()) {
            cleanup(enrollment, to, now);
            notes = runFollowUps(enrollment, to, now);
        }
        log.info("학생 상태 변경: enrollmentId={}, {} → {}", enrollmentId, from, to);
        return new StatusChangeResult(enrollment, notes);
    }

    /**
     * 도메인별 후속처리.
     *
     * <p><b>같은 트랜잭션에서 돈다.</b> 하나라도 실패하면 상태 변경까지 되돌아간다 —
     * 상태는 퇴원인데 급식은 그대로인 어중간한 상태를 만들지 않기 위해서다.
     */
    private List<EnrollmentStatusFollowUp.Note> runFollowUps(StudentEnrollment enrollment,
                                                             EnrollmentStatus to, Instant now) {
        return followUps.stream()
                .flatMap(f -> f.onEnrollmentEnded(enrollment, to, now).stream())
                .toList();
    }

    /**
     * 상태 변경 결과.
     *
     * <p>후속처리 결과를 함께 돌려준다 — <b>퇴원 처리한 사람이 그 자리에서 봐야</b>
     * 미납이 남았다는 걸 안다. 로그로만 남기면 화면을 닫는 순간 아무도 모른다.
     */
    public record StatusChangeResult(StudentEnrollment enrollment,
                                     List<EnrollmentStatusFollowUp.Note> followUps) {
    }

    /**
     * 자리·계정 정리.
     *
     * <p>배정은 <b>비우기만</b> 하고 행은 남긴다 — 누가 언제 그 자리를 썼는지가 사라지면
     * 분실물·시설 파손 같은 문의에 답할 수 없다.
     */
    private void cleanup(StudentEnrollment enrollment, EnrollmentStatus to, Instant now) {
        Long enrollmentId = enrollment.getId();

        classAssignmentRepository.findByEnrollmentIdAndActiveTrue(enrollmentId)
                .forEach(ClassAssignment::deactivate);

        seatAssignmentRepository.findActiveByEnrollmentId(enrollmentId)
                .ifPresent(seat -> seat.release(now));

        lockerRepository.findByAssignedEnrollmentIdAndDeletedFalse(enrollmentId)
                .ifPresent(LockerMaster::release);

        // 앱 접근 차단. 계정을 지우지는 않는다 — 재등록 시 같은 사람을 다시 찾아야 한다
        accountRepository.findByStudentId(enrollment.getStudent().getId())
                .ifPresent(Account::deactivate);

        // 수료는 정상 종료라 퇴원일을 남기지 않는다 — 환불 일할계산 대상이 아니다
        if (to == EnrollmentStatus.WITHDRAWN || to == EnrollmentStatus.EXPELLED) {
            enrollment.markWithdrawn(now.atZone(clock.getZone()).toLocalDate());
        } else {
            enrollment.expire();
        }
    }

    private StudentEnrollment load(Long enrollmentId, AuthPrincipal principal) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!principal.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }
}
