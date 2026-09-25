package com.dlab.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.entity.BillingType;
import com.dlab.domain.payment.service.ReceiptStatusService;
import com.dlab.domain.search.entity.SearchType;
import com.dlab.domain.search.service.SavedSearchService;
import com.dlab.domain.statistics.service.StatisticsService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
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
 * 관리자 웹 막힌 것 3차 — 검색조건 저장 화면 구분 · 통계(신규 등록·계열 이름) ·
 * 수납현황 조건 · 오류 문구.
 */
@SpringBootTest
@Transactional
class AdminWebGaps3Test {

    @Autowired SavedSearchService savedSearchService;
    @Autowired StatisticsService statisticsService;
    @Autowired ReceiptStatusService receiptStatusService;
    @Autowired ApprovalService approvalService;
    @Autowired com.dlab.domain.payment.service.BillingService billingService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    short year;
    AuthPrincipal admin;
    Account account;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        Employee employee = new Employee(bundang, "행정");
        em.persist(employee);
        account = Account.forEmployee(employee, "gaps3-admin", "x", false);
        em.persist(account);
        em.flush();

        admin = AuthPrincipal.of(account.getId(), "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String name, String stdNo, TrackType track, LocalDate admitted) {
        Student s = new Student("DL-" + stdNo, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, bundang, year, stdNo, null, GradeType.N_SU);
        e.updateEnrollment(null, track, null);
        if (admitted != null) {
            e.recordAdmission(admitted);
        }
        em.persist(e);
        em.flush();
        return e;
    }

    // ── 검색조건 저장 ────────────────────────────────────────

    @Test
    @DisplayName("★ 화면마다 따로 저장된다 — 출결에서 저장한 조건이 학생 검색에 뜨면 안 된다")
    void savedSearchIsPerScreen() {
        savedSearchService.save(SearchType.STUDENT, "내 반", "{\"classId\":1}", admin);
        savedSearchService.save(SearchType.ATTENDANCE, "내 반", "{\"statuses\":[\"LATE\"]}", admin);

        assertThat(savedSearchService.list(SearchType.STUDENT, admin))
                .singleElement()
                .satisfies(s -> assertThat(s.getConditions()).contains("classId"));
        assertThat(savedSearchService.list(SearchType.ATTENDANCE, admin))
                .singleElement()
                .satisfies(s -> assertThat(s.getConditions()).contains("LATE"));
    }

    // ── 통계 ────────────────────────────────────────────────

    @Test
    @DisplayName("★ 월별 추이에 신규 등록 수가 온다 — 증감만으로는 알 수 없다")
    void monthlyAdmittedCount() {
        LocalDate thisMonth = LocalDate.now(clock).withDayOfMonth(1);
        enroll("김민지", "0001", TrackType.HUMANITIES, thisMonth);
        enroll("박서준", "0002", TrackType.SCIENCE, thisMonth);

        var rows = statisticsService.group(admin, bundang.getId(), year,
                StatisticsService.GroupBy.MONTH, null);

        var current = rows.get(rows.size() - 1);
        assertThat(current.admitted()).isEqualTo(2);
    }

    @Test
    @DisplayName("계열 이름이 한국어로 온다 — 반·월 축과 같은 표기다")
    void trackLabelIsKorean() {
        enroll("김민지", "0003", TrackType.HUMANITIES, LocalDate.now(clock));

        var rows = statisticsService.group(admin, bundang.getId(), year,
                StatisticsService.GroupBy.TRACK, null);

        assertThat(rows).extracting(StatisticsService.GroupRow::label).contains("인문");
        assertThat(rows).extracting(StatisticsService.GroupRow::key).contains("HUMANITIES");
    }

    // ── 수납현황 조건 ───────────────────────────────────────

    @Test
    @DisplayName("★ 청구 항목을 여러 개 고를 수 있고 검색어가 먹는다")
    void receiptStatusFilters() {
        StudentEnrollment minji = enroll("김민지", "0004", TrackType.HUMANITIES, LocalDate.now(clock));
        StudentEnrollment seojun = enroll("박서준", "0005", TrackType.SCIENCE, LocalDate.now(clock));
        em.persist(billing(minji, BillingType.TUITION, "3월 교습비"));
        em.persist(billing(seojun, BillingType.MEAL, "3월 급식비"));
        em.flush();

        var all = receiptStatusService.find(admin, bundang.getId(), year, null, null,
                List.of(BillingType.TUITION, BillingType.MEAL), false, null, null);
        assertThat(all).hasSize(2);

        var mealOnly = receiptStatusService.find(admin, bundang.getId(), year, null, null,
                List.of(BillingType.MEAL), false, null, null);
        assertThat(mealOnly).singleElement()
                .satisfies(r -> assertThat(r.billing().getName()).isEqualTo("3월 급식비"));

        var byName = receiptStatusService.find(admin, bundang.getId(), year, null, null,
                null, false, "김민지", null);
        assertThat(byName).singleElement()
                .satisfies(r -> assertThat(r.billing().getEnrollment().getId())
                        .isEqualTo(minji.getId()));
    }

    private Billing billing(StudentEnrollment enrollment, BillingType type, String name) {
        return new Billing(enrollment, name, type, 100_000, 0,
                LocalDate.now(clock).plusDays(7));
    }

    // ── 청구 중복 ───────────────────────────────────────────

    @Test
    @DisplayName("★ 같은 이름의 특강비를 두 번 청구하면 막는다 — 미납이 두 배로 잡힌다")
    void blocksDuplicateLectureBilling() {
        StudentEnrollment minji = enroll("김민지", "0007", TrackType.HUMANITIES, LocalDate.now(clock));

        billingService.create(admin, minji.getId(), "여름 특강", BillingType.LECTURE,
                200_000, 0, LocalDate.now(clock).plusDays(7));

        assertThatThrownBy(() -> billingService.create(admin, minji.getId(), "여름 특강",
                BillingType.LECTURE, 200_000, 0, LocalDate.now(clock).plusDays(7)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("여름 특강");
    }

    @Test
    @DisplayName("정말 두 번 받는 경우는 중복 허용으로 통과한다")
    void allowsDuplicateWhenExplicit() {
        StudentEnrollment minji = enroll("김민지", "0008", TrackType.HUMANITIES, LocalDate.now(clock));

        billingService.create(admin, minji.getId(), "재수강", BillingType.LECTURE,
                200_000, 0, LocalDate.now(clock).plusDays(7));
        var second = billingService.create(admin, minji.getId(), "재수강", BillingType.LECTURE,
                200_000, 0, LocalDate.now(clock).plusDays(7), true);

        assertThat(second.getId()).isNotNull();
    }

    // ── 오류 문구 ───────────────────────────────────────────

    @Test
    @DisplayName("★ 승인 정책이 없을 때 내부 코드가 아니라 사람 말로 안내한다")
    void approvalPolicyMessageIsHumanReadable() {
        StudentEnrollment minji = enroll("김민지", "0006", TrackType.HUMANITIES, LocalDate.now(clock));

        assertThatThrownBy(() -> approvalService.create(minji, RequestType.FIREWALL_UNLOCK))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("와이파이 해제")
                .hasMessageNotContaining("FIREWALL_UNLOCK");
    }
}
