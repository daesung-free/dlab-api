package com.dlab.api.appconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.appconfig.entity.Terms;
import com.dlab.domain.appconfig.entity.TermsCode;
import com.dlab.domain.appconfig.service.TermsService;
import com.dlab.domain.meal.service.MealOrderService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
 * 약관 지점 범위 · 급식 제3자 제공 동의 (장학 동의서·신입생 동의서 수령분).
 *
 * <p>지키려는 것 — <b>지점 문구가 공통을 대체할 것</b>, <b>남의 지점 문구가 새지 않을 것</b>,
 * <b>약관이 없을 때 급식이 막히지 않을 것</b>, <b>등록하면 실제로 막힐 것</b>.
 */
@SpringBootTest
@Transactional
class TermsScopeTest {

    @Autowired TermsService termsService;
    @Autowired MealOrderService mealOrderService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Academy ilsan;
    Long bundangStudentAccountId;
    Long bundangEnrollmentId;

    @BeforeEach
    void setUp() {
        bundang = new Academy("TS31", "약관분당", LocalTime.of(9, 0));
        ilsan = new Academy("TS32", "약관일산", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(ilsan);

        Student student = new Student("DL-TS-0001", "김민지", "010-1111-2222");
        em.persist(student);
        StudentEnrollment enrollment = new StudentEnrollment(student, bundang, (short) 2026,
                "TS-0001", null, GradeType.HIGH3);
        em.persist(enrollment);
        bundangEnrollmentId = enrollment.getId();

        Account account = Account.forStudent(student, "ts-student", "hash");
        em.persist(account);
        bundangStudentAccountId = account.getId();
        em.flush();
    }

    private Terms common(String code, String version) {
        return termsService.create(null, code, version, code + " 공통", "공통 문구", false, null);
    }

    private Terms forAcademy(Academy academy, String code, String version) {
        return termsService.create(academy.getId(), code, version,
                code + " " + academy.getName(), academy.getName() + " 문구", false, null);
    }

    // ── 지점 범위 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 지점 문구가 있으면 공통을 대체한다 — 둘 다 뜨면 어디에 동의할지 앱이 모른다")
    void academyTermsOverrideCommon() {
        common("SCHOLARSHIP", "1.0");
        forAcademy(bundang, "SCHOLARSHIP", "1.0");
        em.flush();

        List<TermsService.TermsStatus> status = termsService.statusOf(bundangStudentAccountId);

        assertThat(status).singleElement()
                .satisfies(s -> {
                    assertThat(s.terms().getCode()).isEqualTo("SCHOLARSHIP");
                    assertThat(s.terms().getAcademy().getId()).isEqualTo(bundang.getId());
                    assertThat(s.terms().getContent()).isEqualTo("약관분당 문구");
                });
    }

    @Test
    @DisplayName("★ 남의 지점 문구는 보이지 않는다")
    void otherAcademyTermsHidden() {
        forAcademy(ilsan, "SCHOLARSHIP", "1.0");
        em.flush();

        assertThat(termsService.statusOf(bundangStudentAccountId)).isEmpty();
    }

    @Test
    @DisplayName("지점 문구가 없으면 공통이 그대로 보인다")
    void fallsBackToCommon() {
        common("SERVICE", "1.0");
        em.flush();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .singleElement()
                .satisfies(s -> assertThat(s.terms().getAcademy()).isNull());
    }

    @Test
    @DisplayName("★ 같은 code·version이라도 공통본과 지점본은 서로 막지 않는다")
    void commonAndAcademyCoexist() {
        common("SCHOLARSHIP", "1.0");
        forAcademy(bundang, "SCHOLARSHIP", "1.0");
        forAcademy(ilsan, "SCHOLARSHIP", "1.0");
        em.flush();

        // 같은 지점에 같은 버전을 또 넣는 것은 막힌다 — 덮어쓰려는 시도다
        assertThatThrownBy(() -> forAcademy(bundang, "SCHOLARSHIP", "1.0"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TERMS_VERSION_DUPLICATED);
    }

    @Test
    @DisplayName("장학 종류는 컬럼이 아니라 code로 나뉜다 — 셋이 각각 뜬다")
    void scholarshipTypesAreCodes() {
        forAcademy(bundang, "SCHOLARSHIP_REGULAR", "1.0");
        forAcademy(bundang, "SCHOLARSHIP_BANSU", "1.0");
        forAcademy(bundang, "REFUND_CLASS", "1.0");
        em.flush();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .extracting(s -> s.terms().getCode())
                .containsExactlyInAnyOrder("SCHOLARSHIP_REGULAR", "SCHOLARSHIP_BANSU",
                        "REFUND_CLASS");
    }

    // ── 급식 제3자 제공 동의 ──────────────────────────────────

    @Test
    @DisplayName("★ 약관이 아직 없으면 급식을 막지 않는다 — 문구 미확정인데 급식이 멈추면 안 된다")
    void noTermsDoesNotBlockMeal() {
        assertThat(termsService.needsAgreement(bundangStudentAccountId,
                TermsCode.MEAL_THIRD_PARTY)).isFalse();
    }

    @Test
    @DisplayName("★ 약관을 등록하면 그때부터 실제로 막힌다 — 등록이 곧 시행이다")
    void registeringTermsStartsBlocking() {
        common(TermsCode.MEAL_THIRD_PARTY, "1.0");
        em.flush();

        assertThat(termsService.needsAgreement(bundangStudentAccountId,
                TermsCode.MEAL_THIRD_PARTY)).isTrue();

        assertThatThrownBy(() -> mealOrderService.applyByStudent(
                bundangStudentAccountId, bundangEnrollmentId,
                java.time.YearMonth.now(clock), List.of()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.MEAL_THIRD_PARTY_CONSENT_REQUIRED);
    }

    @Test
    @DisplayName("동의하면 통과한다 — 가입 때 미동의였어도 이 시점에 동의하면 신청할 수 있다")
    void agreeingUnblocks() {
        Terms terms = common(TermsCode.MEAL_THIRD_PARTY, "1.0");
        em.flush();

        termsService.record(bundangStudentAccountId, terms.getId(), false);
        assertThat(termsService.needsAgreement(bundangStudentAccountId,
                TermsCode.MEAL_THIRD_PARTY)).isTrue();

        termsService.record(bundangStudentAccountId, terms.getId(), true);
        assertThat(termsService.needsAgreement(bundangStudentAccountId,
                TermsCode.MEAL_THIRD_PARTY)).isFalse();
    }

    @Test
    @DisplayName("★ 문구가 개정되면 다시 받아야 한다 — 옛 업체 동의로 새 업체에 정보를 주면 안 된다")
    void revisionRequiresNewConsent() {
        Terms v1 = common(TermsCode.MEAL_THIRD_PARTY, "1.0");
        em.flush();
        termsService.record(bundangStudentAccountId, v1.getId(), true);
        assertThat(termsService.needsAgreement(bundangStudentAccountId,
                TermsCode.MEAL_THIRD_PARTY)).isFalse();

        // 급식업체가 바뀌어 문구를 개정한다
        common(TermsCode.MEAL_THIRD_PARTY, "2.0");
        em.flush();

        assertThat(termsService.needsAgreement(bundangStudentAccountId,
                TermsCode.MEAL_THIRD_PARTY)).isTrue();
    }

    @Test
    @DisplayName("미동의도 행으로 남는다 — '안 봤다'와 구분돼야 다시 물어볼지 판단할 수 있다")
    void declineIsRecorded() {
        Terms terms = common("MARKETING", "1.0");
        em.flush();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .singleElement()
                .satisfies(s -> assertThat(s.agreed()).isNull());   // 아직 응답 없음

        termsService.record(bundangStudentAccountId, terms.getId(), false);
        em.flush();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .singleElement()
                .satisfies(s -> assertThat(s.agreed()).isFalse());  // 미동의를 골랐음
    }

    // ── 잘못 올린 약관 치우기 ─────────────────────────────────────

    /**
     * ★★ 운영에 테스트 약관이 「필수」로 올라가 있었다 (2026-10-30 프런트 발견).
     *
     * <p>등록 경로만 있고 <b>치우는 경로가 없어서</b> 그대로 남아 있었다 — 앱이 출시되면
     * 사용자에게 <b>「zz_약관확인필수」가 뜬다.</b>
     */
    @Test
    @DisplayName("★ 응답이 없는 약관은 치울 수 있다 — 잘못 올린 것을 거두는 경로다")
    void unansweredTermsCanBeRemoved() {
        Terms terms = common("ZZ_TEST", "1.0");
        em.flush();

        termsService.remove(terms.getId());
        em.flush();
        em.clear();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .as("★ 목록에서 사라진다")
                .isEmpty();
    }

    /**
     * ★★ 한 명이라도 응답했으면 치우지 않는다.
     *
     * <p>동의는 <b>「그때 어떤 문구에 동의했는가」가 근거</b>다. 치우면 <b>그 사람의
     * 동의가 무엇에 대한 것인지 답할 수 없다.</b>
     */
    @Test
    @DisplayName("★★ 동의한 사람이 있으면 치울 수 없다 — 동의의 근거가 사라진다")
    void answeredTermsCannotBeRemoved() {
        Terms terms = common("MARKETING", "1.0");
        em.flush();
        termsService.record(bundangStudentAccountId, terms.getId(), true);
        em.flush();

        assertThatThrownBy(() -> termsService.remove(terms.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("버전을 올려");
    }

    /**
     * ★★ <b>미동의도 응답이다</b> — 치우면 「물어봤는데 거부했다」와 「물어본 적 없다」가
     * 구분되지 않는다.
     */
    @Test
    @DisplayName("★★ 미동의도 응답이다 — 거부한 기록이 있으면 치울 수 없다")
    void declinedTermsCannotBeRemoved() {
        Terms terms = common("MARKETING", "1.0");
        em.flush();
        termsService.record(bundangStudentAccountId, terms.getId(), false);
        em.flush();

        assertThatThrownBy(() -> termsService.remove(terms.getId()))
                .isInstanceOf(BusinessException.class);
    }

    // ── 마케팅 동의 유효기간 (입학동의서 최종본 261029) ──────────────

    /**
     * ★★ 동의일부터 2년이 지나면 「다시 동의 필요」다.
     *
     * <p>최종본: *「보유 및 이용기간: 동의일부터 2년. <b>2년이 지나면 수신 동의 여부를
     * 다시 확인합니다.</b>」*
     *
     * <h2>★ {@code agreed} 는 {@code true} 로 남긴다</h2>
     * <b>동의한 사실 자체는 바뀌지 않았다.</b> 그것을 {@code false} 로 바꾸면
     * <b>「철회」와 구분되지 않는다</b> — 철회는 본인이 한 것이고 만료는 다시 물어봐야
     * 하는 것이라, 화면이 안내할 말이 다르다.
     */
    @Test
    @DisplayName("★★ 마케팅 동의는 2년 지나면 만료된다 — agreed 는 true 로 남는다")
    void marketingExpiresAfterTwoYears() {
        Terms terms = common("MARKETING", "1.0");
        em.flush();
        termsService.record(bundangStudentAccountId, terms.getId(), true);
        em.flush();

        // ★ 동의 시각을 2년 하루 전으로 되돌린다 — 시계를 못 돌리니 데이터를 돌린다
        em.createNativeQuery("""
                        UPDATE term_agreement SET agreed_at = :past
                         WHERE account_id = :account
                        """)
                .setParameter("past", java.time.Instant.now(clock)
                        .minus(java.time.Duration.ofDays(731)))
                .setParameter("account", bundangStudentAccountId)
                .executeUpdate();
        em.flush();
        em.clear();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.agreed()).as("★ 동의한 사실은 그대로다").isTrue();
                    assertThat(status.expired()).as("★ 유효기간이 지났다").isTrue();
                    assertThat(status.effective()).as("★ 그래서 유효하지 않다").isFalse();
                });

        assertThat(termsService.marketingAllowed(bundangStudentAccountId))
                .as("★★ 광고성 발송 대상에서 빠진다")
                .isFalse();
        assertThat(termsService.needsAgreement(bundangStudentAccountId, "MARKETING"))
                .as("★ 다시 물어봐야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("★ 2년이 안 지났으면 유효하다 — 광고성 발송 대상이다")
    void marketingValidWithinTwoYears() {
        Terms terms = common("MARKETING", "1.0");
        em.flush();
        termsService.record(bundangStudentAccountId, terms.getId(), true);
        em.flush();
        em.clear();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.expired()).isFalse();
                    assertThat(status.effective()).isTrue();
                });
        assertThat(termsService.marketingAllowed(bundangStudentAccountId)).isTrue();
    }

    /**
     * ★★ 기간이 없는 약관은 만료되지 않는다.
     *
     * <p>다른 약관의 기간은 「목적 달성 시까지」·「재학기간 동안」처럼 <b>날짜로 셀 수 없는
     * 기준</b>이다. 같은 2년을 적용하면 <b>재학 중인 학생의 필수 동의가 만료되어 앱이
     * 막힌다.</b>
     */
    @Test
    @DisplayName("★★ 마케팅 외에는 만료가 없다 — 같은 2년을 적용하면 재학생 앱이 막힌다")
    void otherTermsNeverExpire() {
        Terms terms = common("PRIVACY_REQUIRED", "1.0");
        em.flush();
        termsService.record(bundangStudentAccountId, terms.getId(), true);
        em.flush();

        em.createNativeQuery("""
                        UPDATE term_agreement SET agreed_at = :past
                         WHERE account_id = :account
                        """)
                .setParameter("past", java.time.Instant.now(clock)
                        .minus(java.time.Duration.ofDays(2000)))
                .setParameter("account", bundangStudentAccountId)
                .executeUpdate();
        em.flush();
        em.clear();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.expired()).isFalse();
                    assertThat(status.effective()).isTrue();
                });
    }

    /**
     * ★ 철회는 만료가 아니다 — 이미 {@code agreed=false} 라 발송에서 빠진다.
     *
     * <p>여기서 또 세면 <b>「철회」와 「만료」가 섞여</b> 화면이 무엇을 안내할지 정하지
     * 못한다.
     */
    @Test
    @DisplayName("★ 철회한 동의는 만료가 아니다 — 두 가지가 섞이면 안내할 말이 갈린다")
    void withdrawnIsNotExpired() {
        Terms terms = common("MARKETING", "1.0");
        em.flush();
        termsService.record(bundangStudentAccountId, terms.getId(), false);
        em.flush();
        em.clear();

        assertThat(termsService.statusOf(bundangStudentAccountId))
                .singleElement()
                .satisfies(status -> {
                    assertThat(status.agreed()).isFalse();
                    assertThat(status.expired()).as("★ 만료가 아니다").isFalse();
                    assertThat(status.effective()).isFalse();
                });
    }
}
