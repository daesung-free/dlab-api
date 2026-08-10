package com.dlab.api.app.routine;

import com.dlab.api.admin.routine.RoutineResponse;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.routine.service.DailyRoutineService;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.service.ParentSignupService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * 앱 — 오늘의 루틴 (A-11).
 *
 * <p><b>조회 전용이다.</b> 시트가 *"학생 앱에 채점·입력 UI를 만들지 않음"*이라고 명시했다 —
 * 오프라인 시험지 기반이라 채점은 교사가 웹에서 한다.
 *
 * <p><b>점수는 공개된 것만 보인다.</b> 교사가 검수 중인 값이 새어나가면
 * 고치기 전 점수가 학생에게 노출된다.
 */
@RestController
@RequestMapping("/api/v1/app/routines")
@RequiredArgsConstructor
public class AppRoutineController {

    private final DailyRoutineService routineService;
    private final ParentSignupService parentSignupService;
    private final AccountRepository accountRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final Clock clock;

    /** 오늘의 루틴. 날짜를 안 주면 오늘이다. */
    @GetMapping("/today")
    public ApiResponse<List<RoutineResponse.Today>> today(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(clock) : date;
        Long enrollmentId = resolveEnrollment(me, studentId).getId();
        return ApiResponse.success(routineService.today(enrollmentId, target).stream()
                .map(RoutineResponse.Today::from).toList());
    }

    /** 기간 이력 — Daily Report의 "데일리테스트 횟수"가 이걸 센다. */
    @GetMapping("/results")
    public ApiResponse<List<RoutineResponse.History>> results(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Long enrollmentId = resolveEnrollment(me, studentId).getId();
        return ApiResponse.success(
                routineService.studentResults(enrollmentId, from, to).stream()
                        .map(RoutineResponse.History::from).toList());
    }

    /** 학생은 본인 것, 학부모는 자녀 것만. 검증은 {@code requireMyChild}가 한다. */
    private StudentEnrollment resolveEnrollment(AuthPrincipal me, Long studentId) {
        Account account = accountRepository.findById(me.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (account.getAccountType() == AccountType.STUDENT && account.getStudent() != null) {
            return enrollmentRepository.findCurrentByStudentId(account.getStudent().getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        }
        if (account.getAccountType() == AccountType.PARENT && account.getGuardian() != null) {
            if (studentId == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회할 자녀를 지정해 주세요.");
            }
            return parentSignupService.requireMyChild(account.getGuardian().getId(), studentId);
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "학생·학부모만 조회할 수 있습니다.");
    }
}
