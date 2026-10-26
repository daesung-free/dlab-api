package com.dlab.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.DiscountPolicy;
import com.dlab.domain.payment.entity.DiscountType;
import com.dlab.domain.payment.repository.DiscountPolicyRepository;
import com.dlab.domain.payment.service.DiscountPolicyService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교습비 할인 정책 (1016 Q3·Q4).
 *
 * <p>지키려는 것 — <b>종류 목록을 몰라도 돌 것</b>,
 * <b>독서실비에 할인이 붙지 않을 것</b>,
 * <b>꺼진 정책이 금액에 영향을 주지 않을 것</b>,
 * <b>월별 규칙이 연중 기본보다 우선할 것</b>.
 */
@SpringBootTest
@Transactional
class DiscountPolicyTest {

    private static final short YEAR = 2026;

    @Autowired EntityManager em;
    @Autowired DiscountPolicyService service;
    @Autowired DiscountPolicyRepository repository;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(8, 20));
        em.persist(bundang);
        em.flush();
    }

    @Test
    @DisplayName("비율 할인은 버림이다 — 올림하면 할인이 규정보다 커진다")
    void 비율은_버림이다() {
        assertThat(DiscountType.RATE.amountOf(660_000, 30)).isEqualTo(198_000);
        // 1원도 올리지 않는다
        assertThat(DiscountType.RATE.amountOf(100_001, 30)).isEqualTo(30_000);
    }

    @Test
    @DisplayName("★ 금액 할인이 정가를 넘지 못한다 — 넘으면 청구액이 음수가 되어 환불까지 흘러간다")
    void 금액은_정가를_넘지_못한다() {
        assertThat(DiscountType.AMOUNT.amountOf(660_000, 900_000)).isEqualTo(660_000);
    }

    @Test
    @DisplayName("★★ 독서실비에는 할인을 붙일 수 없다 — 환불 차감이 정가보다 작아져 학원이 손해를 본다")
    void 독서실비는_거부한다() {
        assertThatThrownBy(() -> service.create(YEAR, bundang.getId(), "X", "독서실 할인",
                DiscountType.RATE, 10, BillingItemType.STUDY_ROOM, null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("할인을 붙일 수 없습니다");
    }

    @Test
    @DisplayName("★★ 꺼진 정책은 금액에 영향을 주지 않는다 — 시드 5종이 전부 꺼져 있다")
    void 꺼진_정책은_돌지_않는다() {
        service.create(YEAR, bundang.getId(), "RATE_30", "30% 할인",
                DiscountType.RATE, 30, BillingItemType.TUITION, null, null, null);
        em.flush();

        assertThat(service.discountAmount(YEAR, bundang.getId(), "RATE_30",
                BillingItemType.TUITION, 3, 660_000))
                .as("켜기 전에는 0 이다")
                .isZero();
    }

    @Test
    @DisplayName("켜면 적용된다")
    void 켜면_적용된다() {
        var policy = service.create(YEAR, bundang.getId(), "RATE_30", "30% 할인",
                DiscountType.RATE, 30, BillingItemType.TUITION, null, null, null);
        service.changeActive(policy.getId(), true);
        em.flush();

        assertThat(service.discountAmount(YEAR, bundang.getId(), "RATE_30",
                BillingItemType.TUITION, 3, 660_000))
                .isEqualTo(198_000);
    }

    @Test
    @DisplayName("★ 월별 규칙이 연중 기본보다 우선한다 — 1021 「1월 30% · 2~3월 20%」가 그 모양이다")
    void 월별_규칙이_우선한다() {
        var always = service.create(YEAR, bundang.getId(), "SEASON", "시즌 할인",
                DiscountType.RATE, 10, BillingItemType.TUITION, null, null, "연중 기본");
        var january = service.create(YEAR, bundang.getId(), "SEASON", "시즌 할인(1월)",
                DiscountType.RATE, 30, BillingItemType.TUITION, (short) 1, (short) 1, null);
        service.changeActive(always.getId(), true);
        service.changeActive(january.getId(), true);
        em.flush();

        assertThat(service.discountAmount(YEAR, bundang.getId(), "SEASON",
                BillingItemType.TUITION, 1, 660_000))
                .as("1월은 30%").isEqualTo(198_000);
        assertThat(service.discountAmount(YEAR, bundang.getId(), "SEASON",
                BillingItemType.TUITION, 5, 660_000))
                .as("월 지정이 없는 달은 연중 기본 10%").isEqualTo(66_000);
    }

    @Test
    @DisplayName("★ 할인이 없으면 0 이다 — 예외를 던지면 할인 없는 학생의 청구가 막힌다")
    void 없으면_0이다() {
        assertThat(service.discountAmount(YEAR, bundang.getId(), "없는코드",
                BillingItemType.TUITION, 3, 660_000))
                .isZero();
    }

    @Test
    @DisplayName("★ 전 지점 공통과 지점 전용을 함께 본다 — 한쪽만 보면 공통 할인이 사라진다")
    void 공통과_지점을_함께_본다() {
        service.create(YEAR, null, "COMMON", "전 지점 할인",
                DiscountType.RATE, 20, BillingItemType.TUITION, null, null, null);
        service.create(YEAR, bundang.getId(), "BRANCH_ONLY", "분당 전용",
                DiscountType.AMOUNT, 50_000, BillingItemType.TUITION, null, null, null);
        em.flush();

        List<DiscountPolicy> list = service.list(YEAR, bundang.getId());
        assertThat(list).extracting(DiscountPolicy::getCode)
                .contains("COMMON", "BRANCH_ONLY");
    }

    @Test
    @DisplayName("적용 월은 시작·종료를 함께 지정해야 한다 — 한쪽만이면 범위가 성립하지 않는다")
    void 월은_짝으로_받는다() {
        assertThatThrownBy(() -> service.create(YEAR, bundang.getId(), "X", "반쪽 범위",
                DiscountType.RATE, 10, BillingItemType.TUITION, (short) 3, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("시작·종료를 함께");
    }

    @Test
    @DisplayName("지우면 목록에서 빠진다 — soft delete 라 과거 청구 추적은 끊기지 않는다")
    void 지우면_빠진다() {
        var policy = service.create(YEAR, bundang.getId(), "TEMP", "임시",
                DiscountType.RATE, 10, BillingItemType.TUITION, null, null, null);
        service.delete(policy.getId());
        em.flush();

        assertThat(service.list(YEAR, bundang.getId()))
                .extracting(DiscountPolicy::getCode).doesNotContain("TEMP");
        assertThat(repository.findById(policy.getId()))
                .as("행 자체는 남아 있다").isPresent();
    }
}
