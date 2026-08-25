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

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return issue(enrollment, month, seatType, discountRate, remainingDays, dueDate);
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
        issued.add(issue(enrollment, month, seatType, discountRate, days, dueDate));

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
