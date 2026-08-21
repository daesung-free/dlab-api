package com.dlab.api.admin.tuition;

import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionMonth;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.service.DailyFeeCalculator;
import com.dlab.domain.user.entity.GradeType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 교습비 가격·교습일수 DTO. */
public final class TuitionRequests {

    private TuitionRequests() {
    }

    /**
     * 가격 등록·수정.
     *
     * <p>{@code academyId}를 비우면 <b>전 지점 공통</b> — 본사만 다룰 수 있다.
     * 지점 행이 있으면 그 지점에서는 공통본 대신 그것이 쓰인다.
     *
     * <p><b>1일 단가·할인가는 받지 않는다.</b> 월 금액과 교습일수에서 전부 파생되므로,
     * 따로 받으면 월 금액을 고쳤을 때 한쪽만 낡은 값으로 남는다.
     */
    public record SavePrice(
            Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @NotNull(message = "학년은 필수입니다.") GradeType gradeType,
            @NotNull(message = "좌석 유형은 필수입니다.") SeatType seatType,
            @PositiveOrZero(message = "교습비는 0 이상이어야 합니다.") int tuitionFee,
            @PositiveOrZero(message = "독서실비는 0 이상이어야 합니다.") int studyRoomFee) {
    }

    /**
     * 월별 교습일수 등록·수정.
     *
     * <p><b>달력 일수가 아니다</b> — 2026년 기준 2월 27일 · 9월 29일이다.
     * 서버는 달력을 상한으로만 확인하고 값 자체는 학원이 아는 대로 받는다.
     */
    public record SaveMonth(
            Long academyId,
            @NotNull(message = "연도는 필수입니다.") Short year,
            @Min(1) @Max(12) int month,
            @Min(1) @Max(31) int teachingDays) {
    }

    /** 등록된 가격 한 줄. */
    public record PriceView(Long id, Long academyId, short year, String gradeType,
                            String seatType, int tuitionFee, int studyRoomFee, int monthlyTotal) {

        public static PriceView from(TuitionPrice price) {
            return new PriceView(price.getId(),
                    price.isCommon() ? null : price.getAcademy().getId(),
                    price.getYear(), price.getGradeType().name(), price.getSeatType().name(),
                    price.getTuitionFee(), price.getStudyRoomFee(), price.monthlyTotal());
        }
    }

    public record MonthView(Long id, Long academyId, short year, int month, int teachingDays) {

        public static MonthView from(TuitionMonth month) {
            return new MonthView(month.getId(),
                    month.isCommon() ? null : month.getAcademy().getId(),
                    month.getYear(), month.getMonth(), month.getTeachingDays());
        }
    }

    /**
     * 단가표 한 줄 — 지금까지 손으로 채우던 표다.
     *
     * @param monthlyStudyRoom <b>할인이 적용되지 않은 정가</b>. 독서실비는 할인이 없다
     */
    public record FeeRow(int discountRate, int teachingDays,
                         int monthlyTuition, int dailyTuition,
                         int monthlyStudyRoom, int dailyStudyRoom, int monthlyTotal) {

        public static FeeRow from(DailyFeeCalculator.Row row) {
            return new FeeRow(row.discountRate(), row.teachingDays(),
                    row.monthlyTuition(), row.dailyTuition(),
                    row.monthlyStudyRoom(), row.dailyStudyRoom(), row.monthlyTotal());
        }
    }
}
