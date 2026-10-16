package com.dlab.api.admin.meal;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.meal.entity.MealClosure;
import com.dlab.domain.meal.entity.MealOrder;
import com.dlab.domain.meal.entity.MealPolicy;
import com.dlab.domain.meal.entity.MealOrderItem;
import com.dlab.domain.meal.entity.MealOrderStatus;
import com.dlab.domain.meal.entity.MealType;
import com.dlab.common.privacy.Masking;
import com.dlab.common.privacy.PersonalDataPolicy;
import com.dlab.domain.meal.service.MealAdminService;
import com.dlab.domain.meal.service.MealBillingService;
import com.dlab.domain.meal.service.MealDailyRosterService;
import com.dlab.domain.meal.service.MealOrderListEnricher;
import com.dlab.domain.meal.service.MealOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 급식 관리 (F-4.5).
 *
 * <p>화면 탭 그대로 — 월별 신청 현황 · 급식 일정 관리 · 결제·취소 내역.
 *
 * <p><b>배식 체크 탭은 없다</b> — 식사체크 방식(I-18)이 미확정이고,
 * QR 1회용 토큰이 앱 동적 QR(D-2)과 같은 건이다.
 */
@Tag(name = "관리자 · 급식 (F-4.5)")
@RestController
@RequestMapping("/api/v1/admin/meals")
@RequiredArgsConstructor
/*
 * ★ <b>조회</b>는 조회 전용에도 열려 있다 — 「보기만」이 그 권한의 뜻이다.
 *   신청 현황·마감일·정책을 못 보면 조회 전용으로 급식 화면이 통째로 빈다.
 *
 * <p><b>쓰기(마감 등록·정책 변경·신청기간 설정·관리자 취소)는 종전대로</b>다.
 *   특히 취소는 환불로 이어져 되돌리기 어렵다.
 */
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF','READONLY')")
public class AdminMealController {

    private final MealAdminService mealAdminService;
    private final MealOrderService mealOrderService;
    private final MealBillingService mealBillingService;
    private final MealOrderListEnricher orderListEnricher;
    private final MealDailyRosterService dailyRosterService;

    /** 월별 신청 현황 — 달력. */
    @GetMapping("/monthly")
    public ApiResponse<List<MealAdminService.DayStatus>> monthly(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.success(mealAdminService.monthly(me, academyId, month));
    }

    /**
     * 일자별 신청 명단 — <b>그날 누가 먹는가</b> (피드백 5-4).
     *
     * <p>{@link #monthly}는 달력 칸의 인원 수까지만 알려준다. 배식대에서 필요한 것은
     * 숫자가 아니라 명단이고, {@code /orders}는 월 단위 주문이라 그날만 추리려면
     * 주문마다 항목을 펼쳐 봐야 한다.
     *
     * <h2>★ 「신청 vs 배식 대조」는 여기 없다 — 만들지 말 것</h2>
     * 배식 태깅(식사체크) 결과가 <b>키오스크에서 우리 쪽으로 들어오는 경로가 아직 없다.</b>
     * 대조 화면을 만들어도 "실제로 먹은 사람" 칸을 채울 값이 없고, <b>빈 칸은 "안 먹었다"로
     * 읽힌다</b> — 그 상태로 내보내면 신청했는데 안 먹은 것처럼 보이는 학생이 전원이 된다.
     * 수신 경로가 정해지면 이 응답에 칸을 더한다.
     *
     * <p>이름은 권한에 따라 가려지고, 가려졌는지는 {@code masked}로 알려준다 — 화면이
     * 모르면 또 가려 이름이 통째로 사라지거나 "잘못 저장됐다"는 오인 문의가 생긴다.
     */
    @GetMapping("/daily")
    public ApiResponse<DailyRosterResponse> daily(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        var roster = dailyRosterService.daily(me.requireAcademyScope(academyId), date);
        return ApiResponse.success(DailyRosterResponse.from(roster, me));
    }

    // ── 급식 일정 관리 ─────────────────────────────────────────

    /** 급식 중단일 목록. */
    @GetMapping("/closures")
    public ApiResponse<List<ClosureResponse>> closures(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month) {
        return ApiResponse.success(
                mealAdminService.closures(me, academyId, month).stream()
                        .map(ClosureResponse::from).toList());
    }

    /**
     * 중단일 등록.
     *
     * <p><b>이미 신청된 건이 함께 취소된다.</b> 응답의 {@code canceledCount}가
     * 환불 대상 건수다 — 화면이 이 값을 띄워 관리자가 알고 저장하게 한다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    @PostMapping("/closures")
    public ApiResponse<ClosureResponse> addClosure(@CurrentAccount AuthPrincipal me,
                                                   @Valid @RequestBody ClosureRequest request) {
        var result = mealAdminService.addClosure(
                me, request.academyId(), request.date(), request.reason());
        return ApiResponse.success(ClosureResponse.from(result.closure(), result.canceledCount()));
    }

    /** 중단일 해제. <b>취소된 신청은 되살리지 않는다</b> — 학생이 다시 신청한다. */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    @DeleteMapping("/closures/{closureId}")
    public ApiResponse<Void> removeClosure(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long closureId) {
        mealAdminService.removeClosure(me, closureId);
        return ApiResponse.empty();
    }

    /**
     * 신청 마감 규칙 조회 — 이용일 D-n.
     *
     * <p><b>미등록이면 404가 아니라 기본값을 내린다.</b> 앱 신청 판정도 미등록을
     * {@code DEFAULT_DEADLINE_DAYS}로 보고 돌아가므로(MealScheduleService), 화면에
     * "없음"이 뜨면 실제로 적용되는 값과 어긋난다. 등록 여부는 {@code registered}로 구분한다.
     */
    @GetMapping("/policy")
    public ApiResponse<PolicyResponse> policy(@CurrentAccount AuthPrincipal me,
                                              @RequestParam(required = false) Long academyId,
                                              @RequestParam short year) {
        return ApiResponse.success(mealAdminService.findPolicy(me, academyId, year)
                .map(PolicyResponse::from)
                .orElseGet(() -> PolicyResponse.unregistered(
                        me.requireAcademyScope(academyId), year)));
    }

    /** 신청 마감 규칙 — 이용일 D-n. */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    @PutMapping("/policy")
    public ApiResponse<Short> saveDeadline(@CurrentAccount AuthPrincipal me,
                                           @Valid @RequestBody DeadlineRequest request) {
        return ApiResponse.success(mealAdminService.saveDeadline(
                me, request.academyId(), request.year(), request.deadlineDays())
                .getDeadlineDays());
    }

    /** 월 접수기간. <b>기간 밖·미등록이면 앱 신청 화면이 열리지 않는다.</b> */
    @GetMapping("/order-windows")
    public ApiResponse<List<WindowResponse>> windows(@CurrentAccount AuthPrincipal me,
                                                     @RequestParam(required = false) Long academyId,
                                                     @RequestParam short year) {
        return ApiResponse.success(mealAdminService.windows(me, academyId, year).stream()
                .map(WindowResponse::from).toList());
    }

    /**
     * 월 접수기간 설정.
     *
     * <p>대상월마다 다르다(5/18~27에 6월분을 받는다). <b>기간 밖에는 신청 화면이
     * 열리지 않고, 미등록도 닫힘</b>으로 본다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    @PutMapping("/order-windows")
    public ApiResponse<WindowResponse> saveWindow(@CurrentAccount AuthPrincipal me,
                                                  @Valid @RequestBody WindowRequest request) {
        // 형식은 WindowRequest 의 @Pattern 이 이미 막는다 — 여기 parse 는 실패하지 않는다
        return ApiResponse.success(WindowResponse.from(mealAdminService.saveWindow(
                me, request.academyId(), YearMonth.parse(request.targetMonth()),
                request.startsOn(), request.endsOn())));
    }

    // ── 결제·취소 내역 ────────────────────────────────────────

    /**
     * 급식 신청 현황. 취소된 끼니는 빠진다.
     *
     * @param academyId 조회할 지점. <b>비우면 내 지점</b>이다.
     *                  전 지점 권한자(본사)는 지정해야 한다
     */
    @GetMapping("/orders")
    public ApiResponse<List<OrderResponse>> orders(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam(required = false) Long academyId,
                                                   @RequestParam
                                                   @DateTimeFormat(pattern = "yyyy-MM")
                                                   YearMonth month) {
        Long resolved = me.requireAcademyScope(academyId);
        List<MealOrder> orders = mealOrderService.findByMonth(resolved, month);
        // ★ 반·결제수단은 여기서 한 번에 모은다 — 행마다 조회하면 쿼리가 주문 수만큼 나간다
        MealOrderListEnricher.Extras extras = orderListEnricher.of(orders);
        return ApiResponse.success(
                orders.stream().map(o -> OrderResponse.from(o, extras)).toList());
    }

    /** 관리자 취소 — <b>기간 제한 없이 즉시</b>. */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    @DeleteMapping("/orders/items/{itemId}")
    public ApiResponse<Void> cancelItem(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long itemId) {
        mealOrderService.cancelByAdmin(me, itemId);
        return ApiResponse.empty();
    }

    /**
     * 급식비 청구 발행 — 주문 한 건.
     *
     * <p>★★ <b>이 경로가 없어서 급식비가 통합 매출장에 들어가지 않았다.</b>
     * 발행 로직({@code MealBillingService})은 처음부터 있었는데 <b>컨트롤러에 붙어 있지
     * 않아 테스트에서만 호출됐다</b> — 화면에는 급식 신청이 보이고 금액도 보이는데
     * 수납 쪽에는 그 돈이 없는 상태였다.
     *
     * @param dueDate 납기일. 비우면 없음 — 청구 자동 발행 시점이 미확정이라(디랩 회신 대기)
     *                지금은 데스크가 넣는다
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    @PostMapping("/orders/{orderId}/billing")
    public ApiResponse<BillingIssued> issueBilling(
            @CurrentAccount AuthPrincipal me, @PathVariable Long orderId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        return ApiResponse.success(BillingIssued.from(
                mealBillingService.issue(me, orderId, dueDate)));
    }

    /**
     * 급식비 청구 일괄 발행 — 그 달 지점 전체.
     *
     * <p>급식은 <b>월 단위 일괄 결제</b>라(A-9) 이쪽이 기본 경로다.
     *
     * <p>★ <b>한 건이 막혀도 나머지는 발행한다.</b> 그리고 <b>건너뛴 주문은 사유와 함께</b>
     * 돌려준다 — 발행 건수만 주면 빠진 학생이 누구인지 화면이 알 수 없고, 단가 미등록으로
     * 빠진 경우라면 <b>그 학생 급식비는 영영 청구되지 않는다.</b>
     *
     * <p><b>두 번 눌러도 안전하다</b> — 이미 발행된 주문은 건너뛴다.
     */
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    @PostMapping("/billings/monthly")
    public ApiResponse<MonthBillingResult> issueMonthlyBilling(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {
        Long resolved = me.requireAcademyScope(academyId);
        return ApiResponse.success(MonthBillingResult.from(
                mealBillingService.issueMonth(me, resolved, month, dueDate)));
    }

    // ── DTO ──────────────────────────────────────────────────

    /** 발행된 청구 한 건. */
    public record BillingIssued(Long billingId, String name, int amount, LocalDate dueDate) {

        static BillingIssued from(Billing b) {
            return new BillingIssued(b.getId(), b.getName(), b.getBilledAmount(),
                    b.getDueDate());
        }
    }

    /**
     * 일괄 발행 결과.
     *
     * @param skipped <b>비어 있어야 정상</b>이다. 사유별로 조치가 다르다 —
     *                「이미 발행」은 그냥 두면 되지만 「단가 미등록」은 손을 써야 한다
     */
    public record MonthBillingResult(int issuedCount, List<BillingIssued> issued,
                                     List<SkippedOrderResponse> skipped) {

        static MonthBillingResult from(MealBillingService.MonthIssueOutcome outcome) {
            return new MonthBillingResult(outcome.issued().size(),
                    outcome.issued().stream().map(BillingIssued::from).toList(),
                    outcome.skipped().stream().map(SkippedOrderResponse::from).toList());
        }
    }

    public record SkippedOrderResponse(Long orderId, String studentNo, String studentName,
                                       String reason) {

        static SkippedOrderResponse from(MealBillingService.SkippedOrder s) {
            return new SkippedOrderResponse(s.orderId(), s.studentNo(), s.studentName(),
                    s.reason());
        }
    }

    /**
     * 일자별 신청 명단.
     *
     * <p><b>점심·저녁을 합치지 않는다.</b> 배식이 따로 돌고 인원도 다르다 — 한 목록에
     * 섞어 끼니 칸으로만 구분하면 화면이 매번 다시 갈라야 하고, 인원 수도 직접 세게 된다.
     *
     * @param masked 이름이 가려졌는지. 가려진 걸 모르면 화면이 또 가려 이름이 사라진다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "MealDailyRosterResponse")
    public record DailyRosterResponse(LocalDate date, int lunchCount, int dinnerCount,
                                      List<DailyRosterRow> lunch, List<DailyRosterRow> dinner,
                                      boolean masked) {

        static DailyRosterResponse from(MealDailyRosterService.Roster roster, AuthPrincipal me) {
            boolean masked = !PersonalDataPolicy.canViewRaw(me);
            return new DailyRosterResponse(roster.date(),
                    roster.lunch().size(), roster.dinner().size(),
                    roster.lunch().stream().map(r -> DailyRosterRow.from(r, masked)).toList(),
                    roster.dinner().stream().map(r -> DailyRosterRow.from(r, masked)).toList(),
                    masked);
        }
    }

    /**
     * 명단 한 행.
     *
     * @param orderItemId 끼니 항목 id — <b>명단에서 바로 취소</b>할 수 있게 함께 내린다
     *                    ({@code DELETE /meals/orders/items/{itemId}}가 이 단위다)
     * @param className   반. 미배정이면 {@code null}
     * @param seatCd      좌석번호. 미배정이면 {@code null}
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "MealDailyRosterRow")
    public record DailyRosterRow(Long orderItemId, Long enrollmentId, String studentNo,
                                 String studentName, String className, String seatCd) {

        static DailyRosterRow from(MealDailyRosterService.Row row, boolean masked) {
            return new DailyRosterRow(row.orderItemId(), row.enrollmentId(), row.studentNo(),
                    masked ? Masking.name(row.studentName()) : row.studentName(),
                    row.className(), row.seatCd());
        }
    }

    public record ClosureRequest(
            Long academyId,
            @NotNull(message = "일자는 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @NotBlank(message = "중단 사유는 필수입니다.") @Size(max = 50) String reason) {
    }

    /** @param canceledCount 함께 취소된 신청 수(환불 대상). 조회 시에는 {@code null} */
    public record ClosureResponse(Long id, LocalDate date, String reason, Integer canceledCount) {

        static ClosureResponse from(MealClosure c) {
            return new ClosureResponse(c.getId(), c.getClosureDate(), c.getReason(), null);
        }

        static ClosureResponse from(MealClosure c, int canceledCount) {
            return new ClosureResponse(c.getId(), c.getClosureDate(), c.getReason(), canceledCount);
        }
    }

    public record DeadlineRequest(
            Long academyId,
            @NotNull @NotNull(message = "연도는 필수입니다.") Short year,
            @NotNull(message = "마감 일수는 필수입니다.") Short deadlineDays) {
    }

    /**
     * @param registered 지점 정책이 실제로 등록됐는지. {@code false}면 {@code deadlineDays}는
     *                   서버 기본값이다 — 화면이 "설정 안 함"과 구분해서 보여줄 수 있다.
     */
    public record PolicyResponse(Long academyId, short year, short deadlineDays,
                                 boolean registered) {

        static PolicyResponse from(MealPolicy p) {
            return new PolicyResponse(p.getAcademy().getId(), p.getYear(),
                    p.getDeadlineDays(), true);
        }

        static PolicyResponse unregistered(Long academyId, short year) {
            return new PolicyResponse(academyId, year,
                    MealPolicy.DEFAULT_DEADLINE_DAYS, false);
        }
    }

    public record WindowRequest(
            Long academyId,
            @NotBlank(message = "대상 월은 필수입니다.")
            @Pattern(regexp = "\\d{4}-\\d{2}", message = "대상 월은 yyyy-MM 형식입니다.")
            String targetMonth,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startsOn,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endsOn) {
    }

    public record WindowResponse(Long id, String targetMonth,
                                 LocalDate startsOn, LocalDate endsOn) {
        static WindowResponse from(com.dlab.domain.meal.entity.MealOrderWindow w) {
            return new WindowResponse(w.getId(), w.month().toString(),
                    w.getStartsOn(), w.getEndsOn());
        }
    }

    /**
     * 급식 신청 한 건 — 화면 목록의 한 행.
     *
     * @param orderNo   표시용 주문번호({@code M2609-000123}). {@code id}에서 파생되며
     *                  뒤 6자리가 곧 {@code id}라 불러준 번호로 되짚을 수 있다
     * @param className 반 이름. <b>미배정이면 {@code null}</b>
     * @param status    결제 라이프사이클. 결제 붙기 전까지 전부 {@code PENDING}이다
     * @param amount    <b>지금 살아 있는 끼니의 합계</b>. 취소가 반영된 실제 이용액이다.
     *                  단가가 안 박힌 옛 주문은 0이다
     * @param billedAmount 발행된 청구액. <b>청구 전이면 {@code null}</b> — 0으로 내리면
     *                  "0원 청구"와 "아직 청구 안 함"이 구분되지 않는다
     * @param refundableAmount 발행 후 취소된 만큼의 환불 대상 금액. 청구 전이면 0
     * @param paymentMethods 수납 수단. <b>미수납이면 빈 목록</b>이고, 분납이면 여러 개다
     */
    public record OrderResponse(Long id, String orderNo, String studentNo, String studentName,
                                String className, String targetMonth, MealOrderStatus status,
                                int activeCount, Integer amount, Integer billedAmount,
                                int refundableAmount, List<String> paymentMethods,
                                Long billingId, List<MealItemResponse> items, Instant createdAt) {

        static OrderResponse from(MealOrder o, MealOrderListEnricher.Extras extras) {
            return new OrderResponse(o.getId(),
                    o.orderNo(),
                    o.getEnrollment().getStudentNo(),
                    o.getEnrollment().getStudent().getName(),
                    extras.className(o),
                    o.month().toString(), o.getStatus(),
                    o.activeItems().size(),
                    o.totalAmount(),
                    o.isBilled() ? o.getBilling().getBilledAmount() : null,
                    o.refundableAmount(),
                    extras.paymentMethods(o),
                    o.isBilled() ? o.getBilling().getId() : null,
                    o.getItems().stream().map(MealItemResponse::from).toList(),
                    o.getCreatedAt());
        }
    }

    /**
     * @param unitPrice 신청 시점 단가 스냅샷. 단가 등록 전 주문은 {@code null}이다
     *                  — 0으로 내리면 "무료"와 "금액 미상"이 구분되지 않는다
     */
    public record MealItemResponse(Long id, LocalDate mealDate, MealType mealType,
                               Integer unitPrice, Instant canceledAt, String cancelPath) {
        static MealItemResponse from(MealOrderItem i) {
            return new MealItemResponse(i.getId(), i.getMealDate(), i.getMealType(),
                    i.getUnitPrice(),
                    i.getCanceledAt(),
                    i.getCancelPath() == null ? null : i.getCancelPath().name());
        }
    }
}
