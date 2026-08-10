package com.dlab.api.app.meal;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.meal.service.MealOrderService;
import com.dlab.domain.meal.service.MealScheduleService;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.service.ParentSignupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/**
 * 앱 — 급식 신청 (A-9).
 *
 * <p>흐름은 <b>월말에 다음 달 한 달치 일괄 신청 → 마감 전까지 취소</b>다.
 *
 * <p><b>★ 가능일을 앱이 판정하지 않는다.</b> A-9가
 * *"서버 mealPolicy 기준 급식 가능일 렌더(앱 자체 판정 금지)"*라고 명시했다 —
 * 임시공휴일이나 급식 중단일이 추가될 때마다 앱을 배포할 수는 없다.
 *
 * <p><b>결제는 없다.</b> 도메인에도 없고(PG 스펙 E-3 대기), 여기서도 신청·취소까지만 한다.
 *
 * <p><b>학부모도 신청할 수 있다</b> — A-9 사용자가 "학생·학부모"다. 자녀 지정 시
 * {@code requireMyChild}로 검증한다.
 */
@RestController
@RequestMapping("/api/v1/app/meals")
@RequiredArgsConstructor
public class AppMealController {

    private final MealOrderService mealOrderService;
    private final MealScheduleService mealScheduleService;
    private final ParentSignupService parentSignupService;
    private final AccountRepository accountRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    /**
     * 신청 화면에 필요한 것 전부 — 가능일·접수기간·마감일수·중단일.
     *
     * <p>달력을 그리는 데 필요한 걸 한 번에 내린다. 나눠 부르면 앱이 여러 번 왕복하고,
     * 그 사이 중단일이 추가되면 화면이 어긋난다.
     */
    @GetMapping("/menu")
    public ApiResponse<MealResponse.Menu> menu(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        StudentEnrollment enrollment = resolveEnrollment(me, studentId);
        Long academyId = enrollment.getAcademy().getId();

        return ApiResponse.success(MealResponse.Menu.of(
                month,
                mealScheduleService.window(academyId, month),
                mealScheduleService.deadlineDays(academyId, enrollment.getYear()),
                mealScheduleService.availableDates(academyId, month),
                mealScheduleService.closures(academyId, month),
                mealScheduleService.isWindowOpen(academyId, month)));
    }

    /**
     * 한 달치 일괄 신청.
     *
     * <p><b>전부-아니면-전무다</b> — 한 건만 어긋나도 전체가 거부된다. 결제가 붙으면
     * 금액이 한 덩어리로 확정돼야 해서, 부분 반영으로 만들면 그때 갈아엎게 된다.
     */
    @PostMapping("/orders")
    public ApiResponse<MealResponse.Order> apply(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @Valid @RequestBody MealRequests.Apply request) {
        StudentEnrollment enrollment = resolveEnrollment(me, studentId);
        YearMonth month = YearMonth.from(request.selections().get(0).date());

        mealOrderService.apply(enrollment.getId(), month,
                request.selections().stream()
                        .map(s -> new MealOrderService.MealSelection(s.date(), s.mealType()))
                        .toList());

        // ★ 저장 결과를 다시 읽어 내린다. addItem은 메모리에만 넣으므로 flush 전에는
        //   항목 id가 비어 있고, 그러면 앱이 신청 직후 취소를 하려 해도 대상을 지목할 수 없다.
        return ApiResponse.success(MealResponse.Order.from(
                mealOrderService.findMine(enrollment.getId(), month)));
    }

    /**
     * 내 신청 내역.
     *
     * <p>항목마다 {@code cancelable}을 함께 내린다 — 마감(D-n)이 지난 건은 앱이 버튼을
     * 비활성으로 그려야 눌렀다가 거절당하지 않는다.
     */
    @GetMapping("/orders")
    public ApiResponse<MealResponse.Order> myOrder(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long studentId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        StudentEnrollment enrollment = resolveEnrollment(me, studentId);
        var order = mealOrderService.findMine(enrollment.getId(), month);
        if (order == null) {
            return ApiResponse.success(null);
        }

        Long academyId = enrollment.getAcademy().getId();
        short year = enrollment.getYear();
        List<MealResponse.Item> items = order.activeItems().stream()
                .map(item -> MealResponse.Item.from(item,
                        mealScheduleService.isBeforeDeadline(academyId, year, item.getMealDate())))
                .toList();

        return ApiResponse.success(new MealResponse.Order(
                order.getId(), order.month().toString(), order.getStatus().name(), items));
    }

    /** 항목 단위 취소. 마감(D-n)이 지나면 거절된다 — 관리자는 제한 없이 취소할 수 있다. */
    @DeleteMapping("/orders/items/{itemId}")
    public ApiResponse<Void> cancel(@CurrentAccount AuthPrincipal me,
                                    @RequestParam(required = false) Long studentId,
                                    @PathVariable Long itemId) {
        StudentEnrollment enrollment = resolveEnrollment(me, studentId);
        mealOrderService.cancelByStudent(enrollment.getId(), itemId);
        return ApiResponse.empty();
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
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "신청할 자녀를 지정해 주세요.");
            }
            return parentSignupService.requireMyChild(account.getGuardian().getId(), studentId);
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "학생·학부모만 이용할 수 있습니다.");
    }
}
