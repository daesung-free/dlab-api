package com.dlab.api.admin.tuition;

import jakarta.validation.constraints.NotNull;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingItem;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.service.TuitionBillingService;
import com.dlab.domain.payment.service.TuitionPricingService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 교습비 청구 발행 (F-4.8-1 · 0820 규정).
 *
 * <h2>금액을 손으로 넣지 않는다</h2>
 * 학생·월·좌석유형·할인율만 주면 <b>가격 마스터에서 계산</b>된다.
 * 데스크가 660,000÷27 같은 나눗셈을 할 일이 없다.
 *
 * <h2>★ 입학 처리는 청구가 두 건 나갈 수 있다</h2>
 * 1일 입학이 아니면 <b>당월 일할 + 다음달 정액</b>이다 —
 * 규정의 <i>"최초 입학시 1개월 미만으로 결제될 경우 다음달까지 함께 납부"</i>.
 * 화면에서 두 건이 뜨는 게 정상이므로 하나로 합쳐 보여주지 말 것.
 */
@Tag(name = "관리자 · 교습비 청구 발행 (F-4.8-1)")
@RestController
@RequestMapping("/api/v1/admin/tuition/billings")
@RequiredArgsConstructor
public class AdminTuitionBillingController {

    private final TuitionBillingService billingService;
    private final TuitionPricingService pricingService;
    private final com.dlab.domain.user.repository.StudentEnrollmentRepository enrollmentRepository;

    /**
     * 월 청구 발행.
     *
     * <p>{@code remainingDays}를 비우면 <b>월 정액</b>이다 — 1일 단가 × 일수로 하면
     * 절사분만큼 모자란 금액이 청구된다.
     */
    @PostMapping("/monthly")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<BillingView> issueMonthly(@CurrentAccount AuthPrincipal me,
                                                 @Valid @RequestBody IssueMonthly request) {
        // ★★ 정책 코드를 주면 ★ 서버가 할인율을 찾는다 (디랩 「적용」 요청).
        //    ⚠️ 두 경로를 함께 두되 ★ 정책이 이긴다 — 둘 다 주면 어느 쪽인지 모호해지고,
        //    사람이 넣은 값이 정책을 조용히 덮으면 「정책을 켰는데 안 붙는다」가 된다.
        var billing = request.usesPolicy()
                ? billingService.issueMonthlyByPolicy(
                        me, request.enrollmentId(), request.yearMonth(),
                        request.seatType(), request.discountCodes(), request.remainingDays(),
                        request.dueDate(), request.serviceFrom())
                : billingService.issueMonthly(
                        me, request.enrollmentId(), request.yearMonth(),
                        request.seatType(), request.discountRateOrZero(), request.remainingDays(),
                        request.dueDate(), request.discountSource(), request.serviceFrom());
        // ★ 발행 응답에도 계산 근거를 싣는다 — 데스크가 「왜 이 금액인가」에 답해야 한다
        return ApiResponse.success(BillingView.from(billing,
                breakdownOf(me, request.enrollmentId(), request.yearMonth(),
                        request.seatType(), request.discountRateOrZero(),
                        request.remainingDays())));
    }

    /**
     * 등록 <b>전</b> 미리보기 (1016 피드백 2-4).
     *
     * <p>★ <b>저장하지 않는다.</b> 디랩 요청이 <i>"일할계산의 과정과 값이 함께 출력"</i>인데,
     * 그건 <b>등록을 누르기 전에</b> 봐야 의미가 있다 — 누른 뒤에 보면 이미 청구가 나갔다.
     *
     * <p>⚠️ {@code remainingDays} 는 <b>제안값이지 확정값이 아니다</b>
     * ({@code suggestRemainingDays} 주석). 화면에서 고칠 수 있게 두고
     * 「서버 제안 12일 — 고칠 수 있습니다」로 적는 편이 낫다.
     */
    @GetMapping("/preview")
    public ApiResponse<TuitionPricingService.Breakdown> preview(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long enrollmentId,
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
            @RequestParam SeatType seatType,
            @RequestParam(required = false) Integer discountRate,
            @RequestParam(required = false) Integer remainingDays) {
        return ApiResponse.success(breakdownOf(me, enrollmentId, month, seatType,
                discountRate == null ? 0 : discountRate, remainingDays));
    }

    /**
     * 여러 학생 한 번에 (1016 피드백 2-1).
     *
     * <p>★★ <b>한 건이 막혀도 나머지는 발행한다.</b> 매월 수백 명이라 전체 롤백이면
     * <b>한 명이 중복 청구라는 이유로 전부 막힌다.</b> 그리고 <b>왜 빠졌는지</b>를
     * 건별로 돌려준다 — 건수만 주면 데스크가 수백 명 중 누가 안 됐는지 찾을 수 없다.
     *
     * <p>⚠️ <b>응답에 breakdown 을 싣지 않는다.</b> 수백 건이면 전송량이 그만큼 늘고,
     * 일괄 등록 화면이 건별 계산식을 펼쳐 보여줄 자리가 아니다 — 근거가 필요하면
     * {@code GET /preview} 로 한 건씩 본다.
     */
    @PostMapping("/monthly/bulk")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<BulkResultView> issueMonthlyBulk(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody IssueMonthlyBulk request) {
        return ApiResponse.success(BulkResultView.from(billingService.issueMonthlyBulk(
                me, request.yearMonth(), request.dueDate(), request.toTargets())));
    }

    private TuitionPricingService.Breakdown breakdownOf(
            AuthPrincipal me, Long enrollmentId, YearMonth month, SeatType seatType,
            int discountRate, Integer remainingDays) {

        var enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new com.dlab.common.exception.BusinessException(
                        com.dlab.common.exception.ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new com.dlab.common.exception.BusinessException(
                    com.dlab.common.exception.ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        var price = pricingService.price((short) month.getYear(), enrollment.getGrade(),
                seatType, enrollment.getAcademy().getId());
        return pricingService.breakdown(price, (short) month.getYear(), month.getMonthValue(),
                enrollment.getAcademy().getId(), discountRate, remainingDays);
    }

    /**
     * 입학 청구 발행. <b>1일 입학이 아니면 두 건</b>이 나간다.
     *
     * <p>{@code remainingDays}를 비우면 서버가 제안값을 쓴다 —
     * {@code GET /remaining-days}로 미리 받아 화면에서 고칠 수 있게 하는 편이 낫다.
     */
    @PostMapping("/admission")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<List<BillingView>> issueOnAdmission(
            @CurrentAccount AuthPrincipal me, @Valid @RequestBody IssueAdmission request) {

        return ApiResponse.success(billingService.issueOnAdmission(
                        me, request.enrollmentId(), request.admissionDate(), request.seatType(),
                        request.discountRateOrZero(), request.remainingDays(), request.dueDate())
                .stream().map(BillingView::from).toList());
    }

    /**
     * 남은 교습일수 <b>제안값</b>.
     *
     * <p>⚠️ 확정값이 아니다. 서버는 그 달 교습일수 총합만 알고 <b>어느 날이 휴원일인지는
     * 모른다.</b> 화면에서 데스크가 고칠 수 있어야 한다 — 서버가 확정하면 조용히 틀린다.
     */
    @GetMapping("/remaining-days")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<Integer> remainingDays(
            @RequestParam Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate admissionDate) {
        return ApiResponse.success(billingService.suggestRemainingDays(academyId, admissionDate));
    }

    /**
     * @param month         청구 대상 월. {@code yyyy-MM} — <b>청구 1건 = 한 달분</b>이다
     * @param remainingDays 비우면 월 정액. 중도 입·퇴원이면 실제 다니는 교습일수
     */
    /**
     * @param discountSource 할인 근거(장학명). 장학을 끌어와 채운 값이면 그 이름을 함께 보낸다 —
     *                       <b>발행 시점 스냅샷으로 박힌다.</b> 수기 할인이면 비운다
     * @param serviceFrom    일할 청구의 시작일. ⚠️ <b>비우면 대상 기간을 저장하지 않는다</b> —
     *                       {@code remainingDays} 는 교습일수 기준이라 말일에서 역산하면 어긋난다
     */
    public record IssueMonthly(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            @NotBlank(message = "월은 필수입니다.")
            @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])",
                    message = "월은 yyyy-MM 형식이어야 합니다.") String month,
            @NotNull(message = "좌석 유형은 필수입니다.") SeatType seatType,
            @Min(0) @Max(100) Integer discountRate,
            @Min(1) @Max(31) Integer remainingDays,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
            @Size(max = 100) String discountSource,
            /**
             * ★★ 적용할 할인 정책 코드 <b>여럿</b>. 주면 서버가 <b>합연산</b>으로
             * 계산한다 — {@code discountRate} · {@code discountSource} 는 무시된다.
             *
             * <p>★ 디랩 1021 회신: <b>「다중 할인 혜택은 합연산」</b> —
             * 장학 10% + 친구추천 10% + 경쟁사 이전 10% = <b>30%</b> 를 교습비에 적용한다.
             * <b>곱연산이 아니므로 순서가 없다.</b>
             *
             * <p>★ <b>장학도 할인의 한 종류다</b> — 장학생을 막지 않는다.
             *
             * <p>⚠️ <b>할인율 직접 입력을 없애지 않았다</b> — 정책에 없는 예외가 실제로
             * 있고(지점 재량·일회성 조정), 막으면 데스크가 청구를 아예 못 낸다.
             */
            List<@Size(max = 40) String> discountCodes,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceFrom) {

        /** 생략 가능 — 없으면 0. primitive 로 두면 생략만으로 역직렬화가 깨진다. */
        /** ★ 정책으로 발행하는가. 코드가 있으면 그쪽이다. */
        public boolean usesPolicy() {
            return discountCodes != null && discountCodes.stream()
                    .anyMatch(c -> c != null && !c.isBlank());
        }

        public int discountRateOrZero() {
            return discountRate == null ? 0 : discountRate;
        }

        public YearMonth yearMonth() {
            return YearMonth.parse(month);
        }
    }

    public record IssueAdmission(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            @NotNull(message = "입학일은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate admissionDate,
            @NotNull(message = "좌석 유형은 필수입니다.") SeatType seatType,
            @Min(0) @Max(100) Integer discountRate,
            @Min(1) @Max(31) Integer remainingDays,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate) {

        /** 생략 가능 — 없으면 0. primitive 로 두면 생략만으로 역직렬화가 깨진다. */
        public int discountRateOrZero() {
            return discountRate == null ? 0 : discountRate;
        }
    }

    /**
     * @param items 교습비·독서실비. <b>환불 산식이 달라</b> 나눠 든다 —
     *              청구 금액({@code billedAmount})은 합계 한 줄이고 영수증도 그대로다
     */
    /**
     * @param serviceFrom     대상 기간 시작일. ⚠️ <b>일할 청구인데 시작일을 모르면 {@code null}</b> 이다
     * @param wholeMonth      월 전체 청구인가. ★ 화면은 월 전체면 {@code 2026-10},
     *                        아니면 {@code 2026-10 (10/15~10/31)} 로 적는다 —
     *                        <b>전부 괄호가 붙으면 중도입학 건이 눈에 안 띈다</b>
     * @param discountSource  할인 근거(장학명) 스냅샷. 수기 할인이면 {@code null}
     * @param breakdown       일할 계산 근거. <b>발행 응답과 미리보기에 같은 모양으로 실린다</b>
     */
    public record BillingView(Long id, String name, Short serviceYear, Short serviceMonth,
                              LocalDate serviceFrom, LocalDate serviceTo, boolean wholeMonth,
                              String discountSource,
                              int suppliedAmount, int discountAmount, int billedAmount,
                              LocalDate dueDate, List<ItemView> items,
                              TuitionPricingService.Breakdown breakdown) {

        static BillingView from(Billing b) {
            return from(b, null);
        }

        static BillingView from(Billing b, TuitionPricingService.Breakdown breakdown) {
            return new BillingView(b.getId(), b.getName(), b.getServiceYear(), b.getServiceMonth(),
                    b.getServiceFrom(), b.getServiceTo(), b.coversWholeMonth(),
                    b.getDiscountSource(),
                    b.getSuppliedAmount(), b.getDiscountAmount(), b.getBilledAmount(),
                    b.getDueDate(), b.activeItems().stream().map(ItemView::from).toList(),
                    breakdown);
        }
    }

    public record ItemView(String itemType, int suppliedAmount, int discountAmount,
                           int billedAmount) {

        static ItemView from(BillingItem i) {
            return new ItemView(i.getItemType().name(), i.getSuppliedAmount(),
                    i.getDiscountAmount(), i.getBilledAmount());
        }
    }

    /**
     * 일괄 청구 요청.
     *
     * <p>★ <b>대상을 서버가 고르지 않는다.</b> 화면이 학생 목록에서 체크한 것을 보낸다 —
     * 검색 조건(반·학년·좌석유형)으로 서버가 추려 발행하게 만들면 <b>데스크가 누구에게
     * 나갈지 보지 못한 채</b> 수백 건이 발행된다.
     *
     * <p>할인율·좌석유형이 <b>행마다 다르다</b> — 장학이 걸린 학생이 섞여 있다.
     */
    public record IssueMonthlyBulk(
            @NotBlank(message = "월은 필수입니다.")
            @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])",
                    message = "월은 yyyy-MM 형식이어야 합니다.") String month,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
            @NotEmpty(message = "대상 학생이 없습니다.") @Valid List<BulkTargetRequest> targets) {

        public YearMonth yearMonth() {
            return YearMonth.parse(month);
        }

        public List<TuitionBillingService.BulkTarget> toTargets() {
            return targets.stream()
                    .map(t -> new TuitionBillingService.BulkTarget(t.enrollmentId(),
                            t.seatType(), t.discountRate(), t.remainingDays(),
                            t.discountSource(), t.serviceFrom()))
                    .toList();
        }
    }

    public record BulkTargetRequest(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            @NotNull(message = "좌석 유형은 필수입니다.") SeatType seatType,
            @Min(0) @Max(100) Integer discountRate,
            @Min(1) @Max(31) Integer remainingDays,
            @Size(max = 100) String discountSource,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate serviceFrom) {
    }

    /**
     * 일괄 발행 결과.
     *
     * @param failed <b>비어 있어야 정상</b>이다. 건별 사유가 있어야 데스크가 수백 명 중
     *               누가 왜 빠졌는지 알 수 있다 — 「중복이라 건너뜀」과 「가격 미등록」은
     *               할 일이 전혀 다르다
     */
    public record BulkResultView(int issuedCount, List<BillingView> issued,
                                 List<FailureView> failed) {

        static BulkResultView from(TuitionBillingService.BulkResult r) {
            return new BulkResultView(r.issued().size(),
                    r.issued().stream().map(BillingView::from).toList(),
                    r.failed().stream()
                            .map(f -> new FailureView(f.enrollmentId(), f.reason())).toList());
        }
    }

    public record FailureView(Long enrollmentId, String reason) {
    }
}
