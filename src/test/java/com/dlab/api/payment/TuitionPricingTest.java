package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionMonth;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.service.TuitionPricingService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교습비 가격 마스터 (0820 규정).
 *
 * <p>지키려는 것은 셋이다 — <b>지점 행이 공통본을 덮는다</b>,
 * <b>교습일수가 없으면 달력으로 때우지 않는다</b>, <b>한 달을 다 쓰면 월 정액과 같다</b>.
 */
@SpringBootTest
@Transactional
class TuitionPricingTest {

    @Autowired TuitionPricingService pricingService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2099;

    Academy bundang;   // 재학생도 660,000 (예외 지점)
    Academy ilsan;     // 재학생 400,000 (공통)

    @BeforeEach
    void setUp() {
        bundang = new Academy("T1", "가격분당", LocalTime.of(9, 0));
        ilsan = new Academy("T2", "가격일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        // 공통 — 재학생은 400,000
        em.persist(TuitionPrice.common(YEAR, GradeType.HIGH3, SeatType.GENERAL, 400_000, 90_000));
        em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000));
        // 분당만 재학생도 660,000
        em.persist(new TuitionPrice(bundang, YEAR, GradeType.HIGH3, SeatType.GENERAL,
                660_000, 90_000));
        // 1인실은 분당에만
        em.persist(new TuitionPrice(bundang, YEAR, GradeType.N_SU, SeatType.SINGLE,
                660_000, 190_000));

        em.persist(TuitionMonth.common(YEAR, 2, 27));    // ★ 달력은 28일
        em.persist(TuitionMonth.common(YEAR, 3, 31));
        em.flush();
    }

    // ─────────────────────────────────────────── 공통 vs 지점

    @Test
    @DisplayName("지점 행이 없으면 공통 가격을 쓴다")
    void fallsBackToCommon() {
        TuitionPrice price = pricingService.price(
                YEAR, GradeType.HIGH3, SeatType.GENERAL, ilsan.getId());

        assertThat(price.getTuitionFee()).isEqualTo(400_000);
        assertThat(price.isCommon()).isTrue();
    }

    @Test
    @DisplayName("★ 지점 행이 있으면 그것만 쓴다 — 합치면 같은 상품이 두 번 나온다")
    void academyRowOverridesCommon() {
        TuitionPrice price = pricingService.price(
                YEAR, GradeType.HIGH3, SeatType.GENERAL, bundang.getId());

        assertThat(price.getTuitionFee()).isEqualTo(660_000);
        assertThat(price.isCommon()).isFalse();
    }

    @Test
    @DisplayName("1인실이 없는 지점은 오류다 — 없는 상품을 공통값으로 팔면 안 된다")
    void missingSeatTypeFails() {
        assertThatThrownBy(() -> pricingService.price(
                YEAR, GradeType.N_SU, SeatType.SINGLE, ilsan.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TUITION_PRICE_NOT_FOUND);
    }

    // ─────────────────────────────────────────── 교습일수

    @Test
    @DisplayName("등록된 교습일수를 쓴다 — 2월은 달력(28)이 아니라 27일이다")
    void usesRegisteredTeachingDays() {
        assertThat(pricingService.teachingDays(YEAR, 2, ilsan.getId())).isEqualTo(27);
    }

    @Test
    @DisplayName("★ 교습일수가 없으면 달력으로 때우지 않고 오류를 낸다")
    void missingMonthFailsInsteadOfFallingBack() {
        assertThatThrownBy(() -> pricingService.teachingDays(YEAR, 9, ilsan.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.TEACHING_DAYS_NOT_REGISTERED);
    }

    // ─────────────────────────────────────────── 단가표

    @Test
    @DisplayName("단가표가 할인율 6단계로 나온다 — 손으로 채우던 표다")
    void feeTableCoversAllDiscountRates() {
        var rows = pricingService.feeTable(YEAR, 2, GradeType.N_SU, SeatType.GENERAL,
                ilsan.getId());

        assertThat(rows).hasSize(6);
        assertThat(rows.get(0).dailyTuition()).isEqualTo(24_444);      // 660,000÷27
        assertThat(rows.get(0).dailyStudyRoom()).isEqualTo(3_333);     //  90,000÷27
        // 독서실비는 할인이 없으므로 어느 줄이든 같다
        assertThat(rows).extracting("monthlyStudyRoom").containsOnly(90_000);
    }

    // ─────────────────────────────────────────── 중도 입학

    @Test
    @DisplayName("★ 한 달을 다 쓰면 월 정액과 정확히 같다 — 절사분만큼 모자라면 안 된다")
    void fullMonthEqualsMonthlyAmount() {
        TuitionPrice price = pricingService.price(
                YEAR, GradeType.N_SU, SeatType.GENERAL, ilsan.getId());

        var amounts = pricingService.prorated(price, 27, 27, 0);

        // 24,444 × 27 = 659,988 이 아니라 660,000 이어야 한다
        assertThat(amounts.tuition()).isEqualTo(660_000);
        assertThat(amounts.studyRoom()).isEqualTo(90_000);
    }

    @Test
    @DisplayName("중도 입학은 남은 교습일수만큼만 낸다")
    void proratedByRemainingDays() {
        TuitionPrice price = pricingService.price(
                YEAR, GradeType.N_SU, SeatType.GENERAL, ilsan.getId());

        var amounts = pricingService.prorated(price, 27, 10, 0);

        assertThat(amounts.tuition()).isEqualTo(24_444 * 10);
        assertThat(amounts.studyRoom()).isEqualTo(3_333 * 10);
    }

    @Test
    @DisplayName("남은 일수가 그 달 교습일수보다 클 수 없다")
    void remainingDaysBounded() {
        TuitionPrice price = pricingService.price(
                YEAR, GradeType.N_SU, SeatType.GENERAL, ilsan.getId());

        assertThatThrownBy(() -> pricingService.prorated(price, 27, 28, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("중도 입학에도 할인이 적용된다")
    void proratedWithDiscount() {
        TuitionPrice price = pricingService.price(
                YEAR, GradeType.N_SU, SeatType.GENERAL, ilsan.getId());

        var amounts = pricingService.prorated(price, 27, 27, 50);

        assertThat(amounts.tuition()).isEqualTo(330_000);
        // ★ 독서실비는 할인이 없다
        assertThat(amounts.studyRoom()).isEqualTo(90_000);
    }
}
