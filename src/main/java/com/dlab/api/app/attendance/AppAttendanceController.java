package com.dlab.api.app.attendance;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.service.AbsenceReasonService;
import com.dlab.domain.attendance.service.AttendanceQueryService;
import jakarta.validation.Valid;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.service.ParentSignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 앱 — 출결 · 상벌점 · 사유출결 조회 (A-18).
 *
 * <p><b>학생과 학부모가 같은 엔드포인트를 쓴다.</b> A-18 사용자가 "학생·학부모"이고
 * 보는 데이터도 같다 — 다른 건 <b>누구 것을 보느냐</b>뿐이다.
 *
 * <p><b>★ 학부모는 {@code studentId}를 넘기고, 서버가 자녀인지 매번 검증한다.</b>
 * 검증을 빠뜨리면 <b>남의 자녀 ID를 넣는 것만으로 그 학생의 출결·상벌점이 열린다.</b>
 * 지점 필터(SearchScope)와 같은 급의 규칙이다.
 *
 * <p>조회 전용이다 — 태깅은 키오스크가, 정정은 관리자 웹이 한다.
 */
@RestController
@RequestMapping("/api/v1/app/attendance")
@RequiredArgsConstructor
public class AppAttendanceController {

    private final AttendanceQueryService attendanceQueryService;
    private final AbsenceReasonService absenceReasonService;
    private final ParentSignupService parentSignupService;
    private final AccountRepository accountRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    /**
     * 기간 출결 — 월 달력·이력 화면.
     *
     * @param studentId 학부모가 자녀를 지정할 때만 쓴다. 학생 본인은 넘기지 않는다
     */
    @GetMapping
    public ApiResponse<List<AttendanceResponse.Daily>> daily(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long enrollmentId = resolveEnrollment(me, studentId).getId();
        return ApiResponse.success(attendanceQueryService.daily(enrollmentId, from, to).stream()
                .map(AttendanceResponse.Daily::from).toList());
    }

    /** 상벌점 — 누적 점수 + 내역. 벌점은 음수로 내려간다. */
    @GetMapping("/penalties")
    public ApiResponse<AttendanceResponse.Penalties> penalties(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId) {
        Long enrollmentId = resolveEnrollment(me, studentId).getId();
        return ApiResponse.success(
                AttendanceResponse.Penalties.from(attendanceQueryService.penalties(enrollmentId)));
    }

    /** 사유출결 — A-18의 "당월 사유출결/벌점 표". */
    @GetMapping("/absence-reasons")
    public ApiResponse<List<AttendanceResponse.AbsenceReasonRow>> absenceReasons(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long enrollmentId = resolveEnrollment(me, studentId).getId();
        return ApiResponse.success(
                attendanceQueryService.absenceReasons(enrollmentId, from, to).stream()
                        .map(AttendanceResponse.AbsenceReasonRow::from).toList());
    }

    /**
     * 사유 제출 (P1-08 · S-6).
     *
     * <p><b>★ 학생 본인만 낼 수 있다.</b> 학부모는 이 신청의 <b>승인자</b>다 —
     * 학부모가 내고 학부모가 승인하면 승인 절차 자체가 무의미해진다. 그래서 조회와 달리
     * {@code studentId}를 받지 않는다.
     *
     * <p>제출하면 <b>관리자 직접 등록과 같은 승인 라우팅</b>을 탄다(승인 주체·타임아웃·
     * 에스컬레이션). 승인 로직을 여기서 다시 만들지 않는다.
     */
    @PostMapping("/absence-reasons")
    public ApiResponse<AttendanceResponse.AbsenceReasonRow> submitAbsenceReason(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody AbsenceReasonRequests.Submit request) {

        Long enrollmentId = requireStudentEnrollment(me).getId();
        return ApiResponse.success(AttendanceResponse.AbsenceReasonRow.from(
                absenceReasonService.submitByStudent(enrollmentId, request.date(),
                        request.type(), request.reasonText(),
                        request.startTime(), request.endTime())));
    }

    /** 사유 취소 — 승인 전까지만. 승인·반려된 건은 이력이라 관리자가 정정한다. */
    @DeleteMapping("/absence-reasons/{reasonId}")
    public ApiResponse<Void> cancelAbsenceReason(@CurrentAccount AuthPrincipal me,
                                                 @PathVariable Long reasonId) {
        absenceReasonService.cancelByStudent(requireStudentEnrollment(me).getId(), reasonId);
        return ApiResponse.empty();
    }

    /**
     * 학생 본인 등록 건.
     *
     * <p>조회용 {@link #resolveEnrollment}와 달리 <b>학부모를 받지 않는다</b> —
     * 위 제출 규칙 때문이다.
     */
    private StudentEnrollment requireStudentEnrollment(AuthPrincipal me) {
        Account account = accountRepository.findById(me.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getAccountType() != AccountType.STUDENT || account.getStudent() == null) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "사유 신청은 학생 본인만 할 수 있습니다.");
        }
        return enrollmentRepository.findCurrentByStudentId(account.getStudent().getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
    }

    /**
     * 계정 → 조회 대상 등록 건.
     *
     * <p><b>학생은 본인 것만, 학부모는 자녀 것만</b> 볼 수 있다.
     * 학생이 {@code studentId}를 넣어 남의 것을 보려 해도 무시하고 본인 것을 돌려준다 —
     * 거절하지 않는 이유는, 앱이 실수로 넣었을 때 화면이 깨지는 것보다 낫기 때문이다.
     */
    private StudentEnrollment resolveEnrollment(AuthPrincipal me, Long studentId) {
        Account account = accountRepository.findById(me.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getAccountType() == AccountType.STUDENT && account.getStudent() != null) {
            return enrollmentRepository.findCurrentByStudentId(account.getStudent().getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        }
        if (account.getAccountType() == AccountType.PARENT && account.getGuardian() != null) {
            if (studentId == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "조회할 자녀를 지정해 주세요.");
            }
            // ★ 여기가 방어선이다. 남의 자녀 ID면 NOT_MY_CHILD로 막힌다
            return parentSignupService.requireMyChild(account.getGuardian().getId(), studentId);
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "학생·학부모만 조회할 수 있습니다.");
    }
}
