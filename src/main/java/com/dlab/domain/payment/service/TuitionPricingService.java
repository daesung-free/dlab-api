package com.dlab.domain.payment.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionMonth;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.repository.TuitionMonthRepository;
import com.dlab.domain.payment.repository.TuitionPriceRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 교습비 가격·교습일수 조회와 1일 단가 산출 (F-4.10-5).
 *
 * <h2>0820 요청을 담당한다</h2>
 * <i>"교습비를 입력하면 그달의 일수로 나누어 1일 교습비가 자동계산 되도록"</i> —
 * 지금까지 손으로 채우던 수백 칸이 <b>월 금액 두 개 입력</b>으로 줄어든다.
 *
 * <h2>★ 전 지점 공통은 본사만 건드린다</h2>
 * 공휴일·성적 양식과 같은 규칙이다. 지점 관리자가 공통 행을 고치면 나머지 지점 가격이
 * 같이 바뀐다. 지점은 <b>자기 지점 행만</b> 만들 수 있고, 그 행이 있으면 그 지점에서는
 * 공통본 대신 그것이 쓰인다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TuitionPricingService {

    private final TuitionPriceRepository priceRepository;
    private final TuitionMonthRepository monthRepository;
    private final AcademyRepository academyRepository;

    // ─────────────────────────────────────────── 조회

    /**
     * 이 학생에게 적용되는 가격.
     *
     * <p>등록 건에서 지점·연도·학년이 나오므로 <b>좌석유형만 받는다</b>.
     * 좌석유형은 배정 결과가 아니라 계약 상품이라 청구 시점에 정해진다.
     */
    @Transactional(readOnly = true)
    public TuitionPrice priceOf(StudentEnrollment enrollment, SeatType seatType) {
        return price(enrollment.getYear(), enrollment.getGrade(), seatType,
                enrollment.getAcademy().getId());
    }

    @Transactional(readOnly = true)
    public TuitionPrice price(short year, GradeType gradeType, SeatType seatType, Long academyId) {
        return priceRepository.findApplicable(year, gradeType, seatType, academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TUITION_PRICE_NOT_FOUND));
    }

    /**
     * 그 달의 교습일수.
     *
     * <p><b>없으면 달력 일수로 대신하지 않고 오류를 낸다.</b> 달력으로 떨어뜨리면
     * 2월이 28일로 계산돼 <b>조용히 틀린 금액</b>이 나간다 — 표는 27일이다.
     * 등록이 안 됐다는 사실이 드러나야 그 해 데이터를 넣는다.
     */
    @Transactional(readOnly = true)
    public int teachingDays(short year, int month, Long academyId) {
        return monthRepository.findApplicable(year, (short) month, academyId)
                .map(TuitionMonth::getTeachingDays)
                .orElseThrow(() -> new BusinessException(ErrorCode.TEACHING_DAYS_NOT_REGISTERED,
                        "%d년 %d월 교습일수가 등록되지 않았습니다.".formatted(year, month)))
                .intValue();
    }

    /**
     * 단가표 — 화면이 그대로 그린다.
     *
     * <p>지금까지 손으로 채우던 표다. 할인율별로 한 줄씩 나온다.
     */
    @Transactional(readOnly = true)
    public List<DailyFeeCalculator.Row> feeTable(short year, int month, GradeType gradeType,
                                                 SeatType seatType, Long academyId) {
        TuitionPrice price = price(year, gradeType, seatType, academyId);
        int days = teachingDays(year, month, academyId);

        return DailyFeeCalculator.DISCOUNT_RATES.stream()
                .map(rate -> DailyFeeCalculator.rowOf(price, days, rate))
                .toList();
    }

    /**
     * 중도 입학 결제액.
     *
     * <p>규정: <i>"1일 입학시 정액 / 1일 이후 입학시 수업일수 × 1일교습비"</i>.
     * 남은 일수는 <b>교습일수 기준</b>이라 달력으로 세지 않는다 —
     * 그래서 입학일이 1일이면 월 정액과 정확히 같아진다.
     *
     * @param remainingDays 입학일부터 그 달 끝까지의 교습일수
     */
    public Amounts prorated(TuitionPrice price, int teachingDays, int remainingDays,
                            int discountRate) {
        if (remainingDays > teachingDays) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "남은 일수가 그 달 교습일수보다 클 수 없습니다.");
        }
        // 한 달을 다 쓰면 나눗셈 오차 없이 월 정액 그대로다 — 1일 단가 × 일수로 하면
        // 절사분만큼 모자란 금액이 청구된다(660,000 vs 22,758×29 = 659,982)
        if (remainingDays == teachingDays) {
            return new Amounts(DailyFeeCalculator.discounted(price.getTuitionFee(), discountRate),
                    price.getStudyRoomFee());
        }
        DailyFeeCalculator.Row row = DailyFeeCalculator.rowOf(price, teachingDays, discountRate);
        return new Amounts(row.dailyTuition() * remainingDays,
                row.dailyStudyRoom() * remainingDays);
    }

    // ─────────────────────────────────────────── 관리

    @Transactional(readOnly = true)
    public List<TuitionPrice> listPrices(AuthPrincipal me, short year, Long academyId) {
        requireScope(me, academyId);
        return priceRepository.findAllByScope(year, academyId);
    }

    @Transactional(readOnly = true)
    public List<TuitionMonth> listMonths(AuthPrincipal me, short year, Long academyId) {
        requireScope(me, academyId);
        return monthRepository.findAllByScope(year, academyId);
    }

    /** 가격 등록·수정. 같은 키가 이미 있으면 금액만 갱신한다 — 중복 행을 만들지 않는다. */
    @Transactional
    public TuitionPrice savePrice(AuthPrincipal me, Long academyId, short year,
                                  GradeType gradeType, SeatType seatType,
                                  int tuitionFee, int studyRoomFee) {
        requireScope(me, academyId);
        if (tuitionFee < 0 || studyRoomFee < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "금액은 0 이상이어야 합니다.");
        }

        return priceRepository.findAllByScope(year, academyId).stream()
                .filter(p -> p.getGradeType() == gradeType && p.getSeatType() == seatType)
                .findFirst()
                .map(existing -> {
                    existing.updateFees(tuitionFee, studyRoomFee);
                    return existing;
                })
                .orElseGet(() -> priceRepository.save(new TuitionPrice(
                        resolveAcademy(academyId), year, gradeType, seatType,
                        tuitionFee, studyRoomFee)));
    }

    /**
     * 월별 교습일수 등록·수정.
     *
     * <p><b>연 1회 12칸이면 끝난다.</b> 이걸 넣지 않으면 그 해 청구에서 1일 교습비를
     * 계산할 수 없다 — 기수 시작 전 체크리스트 항목이다.
     */
    @Transactional
    public TuitionMonth saveMonth(AuthPrincipal me, Long academyId, short year,
                                  int month, int teachingDays) {
        requireScope(me, academyId);
        if (month < 1 || month > 12) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "월은 1~12 사이여야 합니다.");
        }
        if (teachingDays < 1 || teachingDays > 31) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "교습일수는 1~31 사이여야 합니다.");
        }
        // 달력 일수를 넘으면 오타다. 넘는 값이 들어가면 1일 단가가 실제보다 싸진다
        int calendarDays = LocalDate.of(year, month, 1).lengthOfMonth();
        if (teachingDays > calendarDays) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "%d월은 %d일까지입니다.".formatted(month, calendarDays));
        }

        return monthRepository.findAllByScope(year, academyId).stream()
                .filter(m -> m.getMonth() == month)
                .findFirst()
                .map(existing -> {
                    existing.changeTeachingDays(teachingDays);
                    return existing;
                })
                .orElseGet(() -> monthRepository.save(
                        new TuitionMonth(resolveAcademy(academyId), year, month, teachingDays)));
    }

    private Academy resolveAcademy(Long academyId) {
        return academyId == null ? null
                : academyRepository.findById(academyId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    /** {@code academyId}가 {@code null}이면 전 지점 공통 — <b>본사만</b> 다룰 수 있다. */
    private void requireScope(AuthPrincipal me, Long academyId) {
        if (academyId == null) {
            if (me.academyScopeFilter() != null) {
                throw new BusinessException(ErrorCode.TUITION_PRICE_SCOPE_FORBIDDEN);
            }
            return;
        }
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }

    /**
     * 청구에 실릴 두 금액.
     *
     * <p>합계로 합치지 않는다 — 환불 산식이 달라서 <b>끝까지 따로 들고 가야</b> 한다.
     */
    public record Amounts(int tuition, int studyRoom) {

        public int total() {
            return tuition + studyRoom;
        }
    }
}
