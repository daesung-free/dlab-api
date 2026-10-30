package com.dlab.api.admin;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.admission.entity.AdmissionResultStatus;
import com.dlab.domain.admission.entity.AdmissionSource;
import com.dlab.domain.admission.entity.AdmissionType;
import com.dlab.domain.admission.service.AdmissionResultService;
import com.dlab.domain.appconfig.entity.TermAgreement;
import com.dlab.domain.appconfig.entity.Terms;
import com.dlab.domain.appconfig.service.AppUsageService;
import com.dlab.domain.kiosk.service.BranchConfigService;
import com.dlab.domain.statistics.service.StatisticsService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 관리자 웹이 막혀 있던 것 — 반별 통계의 빈 반 · 그해 실적 목록 · 앱 가입·동의 현황 · 지점 설정 비우기.
 */
@SpringBootTest
@Transactional
class AdminWebGapsTest {

    @Autowired StatisticsService statisticsService;
    @Autowired AdmissionResultService admissionResultService;
    @Autowired AppUsageService appUsageService;
    @Autowired BranchConfigService branchConfigService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    short year;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        admin = new AuthPrincipal(1L, "branch", bundang.getId(), Set.of(Role.BRANCH_ADMIN), false, false);
    }

    private StudentEnrollment enroll(String code, String name, String stdNo) {
        Student s = new Student(code, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, bundang, year, stdNo, null, GradeType.N_SU);
        em.persist(e);
        return e;
    }

    @Test
    @DisplayName("★ 재원 0명인 반도 반별 통계에 0으로 나온다 — 빠지면 반 수가 실제와 달라진다")
    void emptyClassCountsAsZero() {
        ClassMaster full = new ClassMaster(bundang, year, "1반", ClassType.FIXED, null);
        ClassMaster empty = new ClassMaster(bundang, year, "2반", ClassType.FIXED, null);
        em.persist(full);
        em.persist(empty);
        em.persist(new ClassAssignment(bundang, enroll("DL-S1", "학생", "0001"), full, ClassType.FIXED));
        em.flush();

        var rows = statisticsService.group(admin, bundang.getId(), year,
                StatisticsService.GroupBy.CLASS, LocalDate.now(clock));

        assertThat(rows).extracting(StatisticsService.GroupRow::label).containsExactly("1반", "2반");
        assertThat(rows).extracting(StatisticsService.GroupRow::count).containsExactly(1L, 0L);
    }

    @Test
    @DisplayName("그해 실적을 학생을 고르지 않고 본다 — 결과·전형·검색어로 거르고 페이징한다")
    void searchAdmissionResultsForYear() {
        StudentEnrollment a = enroll("DL-A1", "김합격", "0001");
        StudentEnrollment b = enroll("DL-A2", "이대기", "0002");
        em.flush();
        admissionResultService.create(admin, a.getId(), AdmissionType.EARLY, "가나대", "국문",
                null, null, null, AdmissionResultStatus.PASSED, AdmissionSource.STAFF, null);
        admissionResultService.create(admin, a.getId(), AdmissionType.REGULAR, "다라대", "철학",
                null, null, null, AdmissionResultStatus.PENDING, AdmissionSource.STAFF, null);
        admissionResultService.create(admin, b.getId(), AdmissionType.EARLY, "가나대", "사학",
                null, null, null, AdmissionResultStatus.FAILED, AdmissionSource.STAFF, null);
        em.flush();

        assertThat(admissionResultService.search(admin, null, year, null, null, null,
                PageRequest.of(0, 50)).getTotalElements()).isEqualTo(3);
        assertThat(admissionResultService.search(admin, null, year, AdmissionResultStatus.PASSED,
                null, null, PageRequest.of(0, 50)).getContent())
                .extracting(r -> r.getDepartmentName()).containsExactly("국문");
        assertThat(admissionResultService.search(admin, null, year, null, AdmissionType.EARLY,
                "이대기", PageRequest.of(0, 50)).getTotalElements()).isEqualTo(1);

        var page = admissionResultService.search(admin, null, year, null, null, null,
                PageRequest.of(1, 2));
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 앱 가입·동의 현황 — 재원생 기준, 동의율은 마지막 행으로 판정한다")
    void appUsage() {
        StudentEnrollment a = enroll("DL-U1", "가입학생", "0001");
        StudentEnrollment b = enroll("DL-U2", "대기학생", "0002");
        enroll("DL-U3", "미가입학생", "0003");
        Account active = Account.forStudent(a.getStudent(), "u1", "x");
        active.approve();
        em.persist(active);
        em.persist(Account.forStudent(b.getStudent(), "u2", "x"));   // 승인 대기
        ParentGuardian mom = new ParentGuardian("엄마", "010-9999-0000", null);
        em.persist(mom);
        em.persist(new StudentGuardianLink(a.getStudent(), mom, (short) 1, true));
        Account parent = Account.forGuardian(mom, "p1", "x");
        em.persist(parent);

        Terms terms = new Terms("SERVICE_" + System.nanoTime(), "1.0", "이용약관", "내용", true,
                Instant.now(clock).minusSeconds(60));
        em.persist(terms);
        em.persist(new TermAgreement(active, terms, true, Instant.now(clock)));
        em.persist(new TermAgreement(parent, terms, true, Instant.now(clock)));
        // 학부모가 철회 — 마지막 행이 미동의다
        em.persist(new TermAgreement(parent, terms, false, Instant.now(clock)));
        em.flush();

        var usage = appUsageService.usage(bundang.getId());

        assertThat(usage.enrolledStudents()).isEqualTo(3);
        assertThat(usage.studentAccounts()).isEqualTo(1);
        assertThat(usage.pendingStudents()).isEqualTo(1);
        assertThat(usage.studentSignupRate()).isEqualTo(33);
        assertThat(usage.parentAccounts()).isEqualTo(1);
        assertThat(usage.studentsWithParent()).isEqualTo(1);
        var rate = usage.terms().stream().filter(t -> t.termsId().equals(terms.getId()))
                .findFirst().orElseThrow();
        assertThat(rate.targetAccounts()).isEqualTo(2);
        assertThat(rate.agreedAccounts()).isEqualTo(1);
        assertThat(rate.rate()).isEqualTo(50);
        // ★ 기간이 없는 약관이라 유효율이 동의율과 같다
        assertThat(rate.effectiveAccounts()).isEqualTo(1);
        assertThat(rate.effectiveRate()).isEqualTo(50);
    }

    /**
     * ★★ 유효기간이 지난 마케팅 동의는 <b>동의자로는 세지만 유효자로는 세지 않는다</b>.
     *
     * <p>고치기 전에는 <b>2년 지난 동의자가 「동의자」로만 잡혀</b> 화면 숫자가 실제
     * 발송 대상과 달랐다(2026-10-30 프런트가 발견).
     *
     * <p>★ {@code agreedAccounts} 를 줄이지 않은 이유 — 숫자가 조용히 줄면 담당자는
     * <b>「동의자가 사라졌다」</b>로 읽는다. 두 값을 나란히 둔다.
     */
    @Test
    @DisplayName("★★ 2년 지난 마케팅 동의는 유효자에서 빠진다 — 동의자로는 남는다")
    void expiredMarketingIsNotEffective() {
        Student s = new Student("UG-M-" + System.nanoTime(), "만료학생", "010-7777-0001");
        em.persist(s);
        var enrollment = new com.dlab.domain.user.entity.StudentEnrollment(
                s, bundang, (short) 2026, "M-0001", null,
                com.dlab.domain.user.entity.GradeType.HIGH3);
        em.persist(enrollment);
        Account account = Account.forStudent(s, "ug-m-" + System.nanoTime(), "x");
        account.approve();   // ACTIVE 로 — 분모는 활성 계정이다
        em.persist(account);

        Terms marketing = new Terms("MARKETING", "1.0", "홍보·마케팅", "내용", false,
                Instant.now(clock).minusSeconds(60));
        em.persist(marketing);
        // ★ 2년 하루 전에 동의했다
        em.persist(new TermAgreement(account, marketing, true,
                Instant.now(clock).minus(java.time.Duration.ofDays(731))));
        em.flush();

        var rate = appUsageService.usage(bundang.getId()).terms().stream()
                .filter(t -> t.termsId().equals(marketing.getId()))
                .findFirst().orElseThrow();

        assertThat(rate.agreedAccounts()).as("★ 동의한 사실은 남는다").isEqualTo(1);
        assertThat(rate.effectiveAccounts()).as("★★ 유효하지 않다").isZero();
    }

    @Test
    @DisplayName("★ 지점 설정은 지금 값을 다시 넣어야 비워진다 — 실수로 비우면 결제·와이파이가 멈춘다")
    void clearBranchConfigRequiresConfirm() {
        branchConfigService.changeNebulaDeviceId(bundang.getId(), "WRONG-DEVICE");
        em.flush();

        assertThatThrownBy(() -> branchConfigService.clearNebulaDeviceId(bundang.getId(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> branchConfigService.clearNebulaDeviceId(bundang.getId(), "OTHER"))
                .isInstanceOf(BusinessException.class);

        branchConfigService.clearNebulaDeviceId(bundang.getId(), "WRONG-DEVICE");
        em.flush();
        // 이미 비었으면 확인값 없이도 성공한다
        branchConfigService.clearNebulaDeviceId(bundang.getId(), null);
    }
}
