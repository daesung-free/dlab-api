package com.dlab.api.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.payment.entity.BillingItemType;
import com.dlab.domain.payment.entity.BillingStandard;
import com.dlab.domain.payment.entity.PaymentMethod;
import com.dlab.domain.payment.entity.SeatType;
import com.dlab.domain.payment.entity.TuitionPrice;
import com.dlab.domain.payment.service.BillingStandardService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
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
 * 청구기준 마스터 (F-4.10-5).
 *
 * <p>지키려는 것은 넷이다 — <b>교습비는 금액을 갖지 않고 단가표 범위를 보여준다</b>,
 * <b>지점 단가가 공통본을 덮은 뒤의 범위여야 한다</b>, <b>공통 기준은 본사만 만든다</b>,
 * <b>코드가 겹치면 막는다</b>.
 */
@SpringBootTest
@Transactional
class BillingStandardTest {

    @Autowired BillingStandardService standardService;
    @Autowired EntityManager em;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2098;

    Academy bundang;
    AuthPrincipal head;     // 전 지점
    AuthPrincipal branch;   // 분당만

    @BeforeEach
    void setUp() {
        bundang = new Academy("B1", "기준분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();

        head = AuthPrincipal.of(1L, "EMPLOYEE", null, List.of(Role.SUPER_ADMIN), true);
        branch = AuthPrincipal.of(2L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    // ─────────────────────────────────────────── 금액 두 방식

    @Test
    @DisplayName("특강비는 금액을 그대로 갖는다")
    void fixedAmount() {
        standardService.create(head, null, YEAR, "bl-sp-01", BillingItemType.LECTURE,
                "단과 특강", null, BillingStandard.AmountSource.FIXED, 320_000,
                "개강 3일 전", PaymentMethod.CARD, (short) 0, null);

        BillingStandardService.Row row = standardService.list(head, YEAR, null, null, null)
                .get(0);

        assertThat(row.amount()).isEqualTo(320_000);
        assertThat(row.amountMin()).isNull();
        // 코드는 전표에 나가므로 대문자로 정리된다
        assertThat(row.code()).isEqualTo("BL-SP-01");
        assertThat(row.itemLabel()).isEqualTo("특강비");
    }

    @Test
    @DisplayName("★ 교습비는 금액을 갖지 않고 단가표 범위를 보여준다 — 대표값을 박으면 실제 청구액과 갈린다")
    void priceMatrixShowsRange() {
        em.persist(TuitionPrice.common(YEAR, GradeType.HIGH3, SeatType.GENERAL, 400_000, 90_000));
        em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000));
        em.flush();

        standardService.create(head, null, YEAR, "BL-TU-01", BillingItemType.TUITION,
                "정규 교습비", "1기", BillingStandard.AmountSource.PRICE_MATRIX, null,
                "매월 25일", PaymentMethod.VBANK, (short) 0, null);

        BillingStandardService.Row row = standardService.list(head, YEAR, null, null, null)
                .get(0);

        assertThat(row.amount()).isNull();
        assertThat(row.amountMin()).isEqualTo(490_000);   // 400,000 + 90,000
        assertThat(row.amountMax()).isEqualTo(750_000);   // 660,000 + 90,000
    }

    @Test
    @DisplayName("★ 지점 단가가 공통본을 덮은 뒤의 범위를 낸다 — 통째로 한쪽만 보면 조합이 사라진다")
    void rangeMergesBranchOverCommon() {
        // 공통: 재학생 400,000 · N수 660,000
        em.persist(TuitionPrice.common(YEAR, GradeType.HIGH3, SeatType.GENERAL, 400_000, 90_000));
        em.persist(TuitionPrice.common(YEAR, GradeType.N_SU, SeatType.GENERAL, 660_000, 90_000));
        // 분당만 재학생도 660,000 — 한 조합만 덮는다
        em.persist(new TuitionPrice(bundang, YEAR, GradeType.HIGH3, SeatType.GENERAL,
                660_000, 90_000));
        em.flush();

        standardService.create(branch, bundang.getId(), YEAR, "BL-TU-01",
                BillingItemType.TUITION, "정규 교습비", "1기",
                BillingStandard.AmountSource.PRICE_MATRIX, null, "매월 25일",
                PaymentMethod.VBANK, (short) 0, null);

        BillingStandardService.Row row =
                standardService.list(branch, YEAR, bundang.getId(), null, null).get(0);

        // 지점 행이 덮였으므로 최저도 750,000이다. 공통만 봤으면 490,000이 나온다
        assertThat(row.amountMin()).isEqualTo(750_000);
        assertThat(row.amountMax()).isEqualTo(750_000);
    }

    @Test
    @DisplayName("★ PRICE_MATRIX로 바꾸면 남아 있던 금액을 지운다")
    void switchingToMatrixClearsAmount() {
        BillingStandard saved = standardService.create(head, null, YEAR, "BL-TU-01",
                BillingItemType.TUITION, "정규 교습비", "1기",
                BillingStandard.AmountSource.FIXED, 750_000, "매월 25일",
                PaymentMethod.VBANK, (short) 0, null);

        standardService.update(head, saved.getId(), "정규 교습비", "1기",
                BillingStandard.AmountSource.PRICE_MATRIX, 750_000, "매월 25일",
                PaymentMethod.VBANK, (short) 0, null);
        em.flush();

        assertThat(saved.getAmount()).isNull();
    }

    @Test
    @DisplayName("FIXED인데 금액이 없으면 거부한다")
    void fixedRequiresAmount() {
        assertThatThrownBy(() -> standardService.create(head, null, YEAR, "BL-SP-01",
                BillingItemType.LECTURE, "단과 특강", null,
                BillingStandard.AmountSource.FIXED, null, null, null, (short) 0, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_REQUEST);
    }

    // ─────────────────────────────────────────── 범위·중복

    @Test
    @DisplayName("★ 전 지점 공통 기준은 지점 관리자가 만들 수 없다")
    void branchCannotCreateCommon() {
        assertThatThrownBy(() -> standardService.create(branch, null, YEAR, "BL-SP-01",
                BillingItemType.LECTURE, "단과 특강", null,
                BillingStandard.AmountSource.FIXED, 320_000, null, null, (short) 0, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.BILLING_STANDARD_SCOPE_FORBIDDEN);
    }

    @Test
    @DisplayName("같은 지점·연도에서 코드가 겹치면 막는다 — 전표에 나가는 값이다")
    void duplicateCode() {
        standardService.create(head, null, YEAR, "BL-SP-01", BillingItemType.LECTURE,
                "단과 특강", null, BillingStandard.AmountSource.FIXED, 320_000,
                null, null, (short) 0, null);

        assertThatThrownBy(() -> standardService.create(head, null, YEAR, "bl-sp-01",
                BillingItemType.LECTURE, "해설 특강", null,
                BillingStandard.AmountSource.FIXED, 90_000, null, null, (short) 0, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        ErrorCode.BILLING_STANDARD_CODE_DUPLICATED);
    }

    @Test
    @DisplayName("공통과 지점은 같은 코드를 쓸 수 있다 — 축이 다르다")
    void sameCodeAcrossScopes() {
        standardService.create(head, null, YEAR, "BL-SP-01", BillingItemType.LECTURE,
                "단과 특강", null, BillingStandard.AmountSource.FIXED, 320_000,
                null, null, (short) 0, null);
        standardService.create(branch, bundang.getId(), YEAR, "BL-SP-01",
                BillingItemType.LECTURE, "분당 단과 특강", null,
                BillingStandard.AmountSource.FIXED, 350_000, null, null, (short) 0, null);
        em.flush();

        assertThat(standardService.list(head, YEAR, null, null, null)).hasSize(1);
        assertThat(standardService.list(branch, YEAR, bundang.getId(), null, null)).hasSize(1);
    }

    // ─────────────────────────────────────────── 상태 필터

    @Test
    @DisplayName("중지한 기준은 지우지 않고 내린다 — 지우면 과거 청구 근거가 끊긴다")
    void inactiveStaysInList() {
        BillingStandard saved = standardService.create(head, null, YEAR, "BL-TU-03",
                BillingItemType.TUITION, "2025 정규 교습비", "3기",
                BillingStandard.AmountSource.FIXED, 1_380_000, null, null, (short) 0, null);

        standardService.changeActive(head, saved.getId(), false);
        em.flush();

        assertThat(standardService.list(head, YEAR, null, null, true)).isEmpty();
        assertThat(standardService.list(head, YEAR, null, null, false)).hasSize(1);
        assertThat(standardService.list(head, YEAR, null, null, null)).hasSize(1);
    }

    @Test
    @DisplayName("환불 기준은 읽기 전용이고 항목별 산식을 그대로 설명한다")
    void refundRulesAreReadOnly() {
        List<BillingStandardService.RefundRule> rules = standardService.refundRules();

        assertThat(rules).extracting(BillingStandardService.RefundRule::itemType)
                .contains(BillingItemType.TUITION, BillingItemType.STUDY_ROOM);
        // 학원법 반환기준은 네 구간이다 — 교습 시작 전(전액) · 1/3 · 1/2 · 그 이후.
        // 원래 3개를 기대하고 있었는데 첫 행(교습 시작 전)이 빠져 있었다.
        assertThat(rules).filteredOn(r -> r.itemType() == BillingItemType.TUITION).hasSize(4);
        assertThat(rules).extracting(BillingStandardService.RefundRule::period)
                .contains("교습 시작 전");
    }
}
