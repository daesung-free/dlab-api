package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.*;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 교습비 청구 발행 (F-4.8-1 · 0820 규정).
 *
 * <h2>가격 마스터를 청구에 연결하는 자리다</h2>
 * {@link BillingService#create}는 금액을 <b>손으로 받는다</b>. 가격 마스터가 생겼으니
 * 여기서는 학생·월·좌석유형·할인율만 받고 <b>금액은 서버가 계산</b>한다.
 * 데스크가 660,000÷27 같은 나눗셈을 하지 않는다.
 *
 * <h2>★ 청구 1건 = 한 달분이고, 항목으로 쪼갠다</h2>
 * 교습비와 독서실비는 <b>환불 산식이 달라</b>(구간 vs 일할) 끝까지 따로 들고 가야 한다.
 * 청구 금액 자체는 합계 한 줄이라 <b>키오스크 영수증은 안 바뀐다.</b>
 *
 * <h2>입학 시 규정</h2>
 * <ul>
 *   <li><b>1일 입학</b> — 월 정액</li>
 *   <li><b>1일 이후 입학</b> — 남은 교습일수 × 1일 단가</li>
 *   <li><b>★ 최초 입학이 1개월 미만이면 다음 달분까지 함께 납부</b> — 규정 명시.
 *       그래서 청구가 <b>2건</b> 생긴다(당월 일할 + 다음달 정액). 한 건에 두 달을 담으면
 *       그중 한 달만 환불하는 계산이 성립하지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TuitionBillingService {

    private final BillingRepository billingRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final TuitionPricingService pricingService;
    private final DiscountPolicyService discountPolicyService;
    private final org.springframework.context.ApplicationContext applicationContext;

    /**
     * 한 달치 교습비 청구.
     *
     * @param remainingDays 그 달에 실제로 다니는 교습일수. {@code null}이면 <b>월 전체</b>다.
     *                      전체면 1일 단가 × 일수가 아니라 <b>월 정액</b>을 쓴다 —
     *                      절사분만큼 모자란 금액이 청구되는 것을 막는다
     */
    @Transactional
    public Billing issueMonthly(AuthPrincipal me, Long enrollmentId, YearMonth month,
                                SeatType seatType, int discountRate, Integer remainingDays,
                                LocalDate dueDate) {
        return issueMonthly(me, enrollmentId, month, seatType, discountRate, remainingDays,
                dueDate, null, null);
    }

    /**
     * 할인 근거·대상 기간까지 받는다 (1016 피드백 2-5 · 2-6).
     *
     * @param discountSource 할인 근거(장학명). <b>발행 시점 스냅샷으로 박힌다</b> —
     *                       나중에 장학이 취소돼도 과거 청구의 근거는 남아야 한다.
     *                       수기 할인이면 비운다
     * @param serviceFrom    일할 청구의 <b>시작일</b>. ⚠️ <b>비우면 기간을 저장하지 않는다</b> —
     *                       {@code remainingDays} 는 교습일수 기준이라(설·추석 제외)
     *                       말일에서 역산하면 어긋난다. 추측값보다 빈 값이 정직하다.
     *                       월 전체 청구는 이 값과 무관하게 1일~말일이 들어간다
     */
    @Transactional
    public Billing issueMonthly(AuthPrincipal me, Long enrollmentId, YearMonth month,
                                SeatType seatType, int discountRate, Integer remainingDays,
                                LocalDate dueDate, String discountSource,
                                LocalDate serviceFrom) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return issue(enrollment, month, seatType, discountRate, remainingDays, dueDate,
                discountSource, serviceFrom);
    }

    /**
     * ★★ 할인 정책으로 발행한다 — <b>할인율을 사람이 고르지 않는다</b>
     * (디랩 「지점별·월별 입력·수정·적용」의 <b>「적용」</b>).
     *
     * <h2>★ 빠져 있던 것은 「적용」 하나였다</h2>
     * 정책 테이블({@code discount_policy})과 산출 로직
     * ({@link DiscountPolicyService#discountAmount})은 이미 있었다. 그런데 <b>청구가
     * 그것을 조회하지 않아</b>, 데스크가 <b>할인율을 눈으로 보고 골라 넣고 있었다.</b>
     *
     * <h2>⚠️ 할인율을 직접 주는 경로를 없애지 않는다</h2>
     * 정책에 없는 예외가 실제로 있고(지점 재량·일회성 조정), 그걸 막으면 <b>데스크가
     * 청구를 아예 못 낸다.</b> 정책 코드를 주면 정책을, 안 주면 준 할인율을 쓴다.
     *
     * <h2>★★ 월이 중요하다 — 정책이 월 범위를 갖는다</h2>
     * 1021 의 「1월 30% · 2~3월 20%」가 그 모양이고, <b>청구 대상 월</b>로 찾아야 한다.
     * 발행한 달로 찾으면 <b>3월분을 2월에 미리 발행할 때 2월 할인이 붙는다.</b>
     *
     * <p>★ {@code discountSource} 에 <b>정책 코드를 박는다</b> — 발행 시점 스냅샷이다.
     * 나중에 정책 값을 고쳐도 <b>이미 낸 청구가 소급해서 바뀌지 않고</b>, 환불 차감이
     * 정상가 기준이라 <b>「그때 어떤 할인이었나」</b>에 답할 수 있어야 한다.
     *
     * @param discountCode 적용할 정책 코드. ★ 없으면 할인 0 이다 — 정책이 없는 것이
     *                     정상이므로 예외를 던지지 않는다({@code discountAmount} 와 같은 판단)
     */
    @Transactional
    public Billing issueMonthlyByPolicy(AuthPrincipal me, Long enrollmentId, YearMonth month,
                                        SeatType seatType, List<String> discountCodes,
                                        Integer remainingDays, LocalDate dueDate,
                                        LocalDate serviceFrom) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        var applied = applyPolicies(enrollment, month, discountCodes, seatType);
        return issue(enrollment, month, seatType, applied.rate(), remainingDays, dueDate,
                // ★ 근거에 ★ 내역을 남긴다 — 「장학 10% + 친구추천 10% = 20%」.
                //   ⚠️ 비율만 남기면 ★ 어느 혜택이 얼마였는지 재현되지 않는다
                applied.source(),
                serviceFrom);
    }

    /**
     * ★★ 정책들을 <b>합연산</b>으로 적용한다 (디랩 1021 회신).
     *
     * <h2>★ 장학도 할인의 한 종류다 — 따로 막지 않는다</h2>
     * 디랩 회신이 <b>「장학 10% + 친구추천 10% + 경쟁사 이전 10% = 30%」</b>를 예로 든다.
     * 장학 동의서도 <b>「장학 대상자는 장학 기준 성적에 맞춰 교습비 할인」</b>이라고 적는다 —
     * <b>장학은 별개 축이 아니라 할인의 한 {@code kind}</b> 다.
     *
     * <p>⚠️ <b>처음에 장학생을 거부하게 만들었던 것은 잘못이었다</b>(2026-10-30).
     * 「중복 규칙이 미확정」이라고 보았는데 <b>1021 에 이미 답이 와 있었다.</b>
     *
     * <h2>⚠️ 교습비만 분모다</h2>
     * 독서실비는 규정상 할인이 없다 — 월 합계로 나누면 <b>교습비 할인이 덜 깎인다.</b>
     */
    private DiscountPolicyService.Applied applyPolicies(
            StudentEnrollment enrollment, YearMonth month,
            List<String> discountCodes, SeatType seatType) {

        int supplied = pricingService.priceOf(enrollment, seatType).getTuitionFee();
        var applied = discountPolicyService.applyAll(
                enrollment.getYear(), enrollment.getAcademy().getId(), discountCodes,
                com.dlab.domain.payment.entity.BillingItemType.TUITION,
                // ★ 청구 대상 월이다 — 발행한 달이 아니다
                month.getMonthValue(), supplied);

        // ★★ 교습비 청구는 ★ 율로만 계산된다(1일 단가·일할이 율 기반이다).
        //    그래서 ★ 금액 할인을 실을 자리가 없다 — 통과시키면 ★ 조용히 사라진다.
        //    ⚠️ 1021 문서도 금액 항목은 「Q3 답에 있으면 규칙을 확인받을 것」으로 남겼다.
        //    ★ 거부가 맞다 — 데스크가 「할인을 걸었는데 안 깎였다」를 알아야 한다.
        if (applied.amount() > 0 && applied.rate() * supplied / 100 != applied.amount()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "금액 할인(정액) 정책은 교습비 청구에 적용할 수 없습니다. "
                            + "교습비는 비율로 계산됩니다 — 비율 정책을 쓰거나 "
                            + "할인율을 직접 입력해 주세요.");
        }
        return applied;
    }

    /**
     * 여러 학생에게 한 번에 (1016 피드백 2-1).
     *
     * <h2>★★ 한 건이 막혀도 나머지는 발행한다</h2>
     * 매월 수백 명이라 전체 롤백으로 만들면 <b>한 명이 중복 청구라는 이유로 전부 막힌다.</b>
     * 그리고 <b>왜 빠졌는지</b>를 건별로 돌려준다 — 건수만 주면 데스크가
     * 수백 명 중 누가 안 됐는지 찾을 방법이 없다(급식비 일괄 발행과 같은 모양이다).
     *
     * <p>⚠️ <b>건마다 트랜잭션을 분리한다.</b> 한 트랜잭션 안에서 예외를 잡으면
     * JPA 세션이 이미 롤백 표시가 되어 <b>뒤따르는 저장이 전부 실패한다</b> —
     * 「한 건만 실패」가 「그 뒤 전부 실패」가 된다.
     */
    public BulkResult issueMonthlyBulk(AuthPrincipal me, YearMonth month, LocalDate dueDate,
                                       List<BulkTarget> targets) {
        List<Billing> issued = new ArrayList<>();
        List<BulkFailure> failed = new ArrayList<>();

        for (BulkTarget t : targets) {
            try {
                issued.add(self().issueMonthly(me, t.enrollmentId(), month, t.seatType(),
                        t.discountRateOrZero(), t.remainingDays(), dueDate,
                        t.discountSource(), t.serviceFrom()));
            } catch (BusinessException e) {
                // 검증 실패라 그 건은 아무것도 쓰지 않았다 — 다음 학생에 영향이 없다
                failed.add(new BulkFailure(t.enrollmentId(), e.getMessage()));
            }
        }

        log.info("교습비 일괄 청구: {}, 대상={}건, 발행={}건, 실패={}건",
                month, targets.size(), issued.size(), failed.size());
        return new BulkResult(issued, failed);
    }

    /**
     * 자기 자신의 프록시 — <b>건별 트랜잭션 분리를 위해 필요하다.</b>
     *
     * <p>같은 빈 안에서 {@code this.issueMonthly(...)} 로 부르면 {@code @Transactional}
     * 이 적용되지 않아(프록시를 거치지 않는다) 전부 한 트랜잭션에 묶인다. 그러면
     * 중복 하나가 세션을 rollback-only 로 만들고 <b>그 뒤 전부 실패한다.</b>
     */
    private TuitionBillingService self() {
        return applicationContext.getBean(TuitionBillingService.class);
    }

    /**
     * 일괄 대상 한 명.
     *
     * <p>할인율은 <b>학생마다 다르다</b> — 장학이 걸린 학생이 섞여 있다.
     */
    public record BulkTarget(Long enrollmentId, SeatType seatType, Integer discountRate,
                             Integer remainingDays, String discountSource,
                             LocalDate serviceFrom) {

        public int discountRateOrZero() {
            return discountRate == null ? 0 : discountRate;
        }
    }

    /** @param failed <b>비어 있어야 정상</b>이다. 사유별로 데스크가 할 일이 다르다 */
    public record BulkResult(List<Billing> issued, List<BulkFailure> failed) {
    }

    public record BulkFailure(Long enrollmentId, String reason) {
    }

    /**
     * 입학 처리 — 규정대로 청구를 만든다.
     *
     * <p>입학일이 1일이 아니면 <b>당월 일할 + 다음달 정액</b> 두 건이 나간다.
     * 규정: <i>"최초 입학시 1개월 미만으로 결제될 경우 다음달 교습비+독서실비까지 함께 납부"</i>.
     *
     * @param remainingDays 입학일부터 그 달 끝까지의 <b>교습일수</b>.
     *                      {@code null}이면 {@link #suggestRemainingDays}로 채운다
     * @return 발행된 청구 목록. 1일 입학이면 1건, 아니면 2건
     */
    @Transactional
    public List<Billing> issueOnAdmission(AuthPrincipal me, Long enrollmentId,
                                          LocalDate admissionDate, SeatType seatType,
                                          int discountRate, Integer remainingDays,
                                          LocalDate dueDate) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        YearMonth month = YearMonth.from(admissionDate);
        List<Billing> issued = new ArrayList<>();

        if (admissionDate.getDayOfMonth() == 1) {
            // 1일 입학은 월 정액 한 건으로 끝난다 — 다음달까지 받을 이유가 없다
            issued.add(issue(enrollment, month, seatType, discountRate, null, dueDate));
            return issued;
        }

        int days = remainingDays != null ? remainingDays
                : suggestRemainingDays(enrollment.getAcademy().getId(), admissionDate);
        // ★ 입학일이 곧 대상 기간 시작일이다 — 여기서는 역산할 필요가 없다
        issued.add(issue(enrollment, month, seatType, discountRate, days, dueDate,
                null, admissionDate));

        // ★ 1개월 미만으로 결제됐으므로 다음 달분을 함께 받는다(규정)
        issued.add(issue(enrollment, month.plusMonths(1), seatType, discountRate, null, dueDate));

        log.info("입학 청구 발행: enrollmentId={}, 입학일={}, 청구 {}건",
                enrollmentId, admissionDate, issued.size());
        return issued;
    }

    /**
     * 남은 교습일수 <b>제안값</b>.
     *
     * <p>⚠️ <b>확정값이 아니다.</b> 우리는 그 달 교습일수 <i>총합</i>만 알고
     * <b>어느 날이 휴원일인지는 모른다</b>({@code tuition_month} 참고). 그래서
     * "입학일 이후 달력일수"와 "그 달 교습일수" 중 작은 값을 제안할 뿐이다.
     *
     * <p>예를 들어 2026년 2월은 교습일수가 27일(설 하루 제외로 추정)인데, 설이 지난
     * 20일에 입학하면 실제로는 9일을 다니지만 이 식은 9를 그대로 낸다 — 맞는다.
     * 반대로 설 이전에 입학하면 하루가 더 계산될 수 있다.
     *
     * <p><b>데스크가 화면에서 고칠 수 있어야 한다.</b> 서버가 확정하면 조용히 틀린다.
     */
    @Transactional(readOnly = true)
    public int suggestRemainingDays(Long academyId, LocalDate admissionDate) {
        YearMonth month = YearMonth.from(admissionDate);
        int teachingDays = pricingService.teachingDays(
                (short) month.getYear(), month.getMonthValue(), academyId);

        int remainingCalendarDays = month.lengthOfMonth() - admissionDate.getDayOfMonth() + 1;
        return Math.max(1, Math.min(remainingCalendarDays, teachingDays));
    }

    // ─────────────────────────────────────────── 내부

    private Billing issue(StudentEnrollment enrollment, YearMonth month, SeatType seatType,
                          int discountRate, Integer remainingDays, LocalDate dueDate) {
        return issue(enrollment, month, seatType, discountRate, remainingDays, dueDate,
                null, null);
    }

    private Billing issue(StudentEnrollment enrollment, YearMonth month, SeatType seatType,
                          int discountRate, Integer remainingDays, LocalDate dueDate,
                          String discountSource, LocalDate serviceFrom) {

        short year = (short) month.getYear();
        short monthValue = (short) month.getMonthValue();

        if (billingRepository.existsTuitionFor(enrollment.getId(), year, monthValue)) {
            throw new BusinessException(ErrorCode.BILLING_ALREADY_ISSUED,
                    "%d년 %d월 교습비 청구가 이미 있습니다.".formatted(year, monthValue));
        }

        Long academyId = enrollment.getAcademy().getId();
        TuitionPrice price = pricingService.price(year, enrollment.getGrade(), seatType, academyId);
        int teachingDays = pricingService.teachingDays(year, monthValue, academyId);

        int days = remainingDays != null ? remainingDays : teachingDays;
        TuitionPricingService.Amounts amounts =
                pricingService.prorated(price, teachingDays, days, discountRate);

        // ★ 정가는 항상 월 정액 기준이 아니라 "이 청구가 대상으로 하는 기간의 정가"다.
        //   환불 차감이 정상가 기준이라(0820 규정), 할인 전 금액을 정확히 들고 있어야
        //   퇴원 정산에서 차감액을 계산할 수 있다
        TuitionPricingService.Amounts supplied =
                pricingService.prorated(price, teachingDays, days, 0);

        int totalSupplied = supplied.total();
        int totalDiscount = totalSupplied - amounts.total();

        Billing billing = billingRepository.save(new Billing(
                enrollment, "%d년 %d월 교습비".formatted(year, monthValue),
                BillingType.TUITION, totalSupplied, totalDiscount, dueDate));
        billing.assignServicePeriod(year, monthValue);
        if (discountSource != null && !discountSource.isBlank()) {
            billing.assignDiscountSource(discountSource);
        }
        // ★ 월 전체면 1일~말일이 확정이다. 일할인데 시작일을 모르면 비워 둔다 —
        //   remainingDays 로 역산하면 설·추석이 든 달에서 어긋난다
        if (days == teachingDays) {
            billing.assignServiceDates(month.atDay(1), month.atEndOfMonth());
        } else if (serviceFrom != null) {
            billing.assignServiceDates(serviceFrom, month.atEndOfMonth());
        }

        // 교습비와 독서실비를 항목으로 나눠 둔다 — 환불 산식이 다르다
        billing.addItem(BillingItemType.TUITION, supplied.tuition(),
                supplied.tuition() - amounts.tuition());
        // 독서실비는 할인이 없다. BillingItem이 한 번 더 막지만 여기서도 0을 넘긴다
        billing.addItem(BillingItemType.STUDY_ROOM, supplied.studyRoom(), 0);

        log.info("교습비 청구: enrollmentId={}, {}년 {}월, 교습일수={}/{}, 정가={}, 할인={}, 청구={}",
                enrollment.getId(), year, monthValue, days, teachingDays,
                totalSupplied, totalDiscount, billing.getBilledAmount());
        return billing;
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }
}
