package com.dlab.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.api.admin.student.StudentResponse;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.service.HomeroomScopeService;
import com.dlab.domain.user.service.StudentService;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 권한 매트릭스 초안대로 담임·조회 전용에 <b>읽기</b>를 연다 (`docs/permission-matrix.md`).
 *
 * <p>예전에는 학생 API 가 본사·지점관리자·행정만 허용해서 <b>담임은 학생 검색을 아예 못 하고
 * 조회 전용은 명단조차 못 봤다</b> — 그 두 계정으로는 화면이 통째로 비어 고장으로 보였다.
 *
 * <p>여기서 지키는 것은 둘이다.
 * <ol>
 *   <li><b>담임은 맡은 학생만</b> 본다 — 목록·상세·엑셀 모두. 범위는 서버가 걸어 화면이 지울 수 없다</li>
 *   <li><b>페이징 뒤에 걸러내지 않는다</b> — 전체 건수가 어긋나면 화면이 빈 페이지를 그린다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class PermissionMatrixTest {

    @Autowired StudentService studentService;
    @Autowired HomeroomScopeService homeroomScopeService;
    @Autowired com.dlab.domain.menu.service.MenuAccessService menuAccessService;
    @Autowired com.dlab.domain.menu.repository.MenuRepository menuRepository;
    @Autowired com.dlab.domain.user.repository.AccountRepository accountRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    short year;
    AuthPrincipal teacher;
    AuthPrincipal admin;
    StudentEnrollment mine;
    StudentEnrollment others;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();
        academy = new Academy("PM01", "권한테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        Teacher me = new Teacher(academy, "내담임", null);
        Teacher other = new Teacher(academy, "남담임", null);
        em.persist(me);
        em.persist(other);

        ClassMaster myClass = new ClassMaster(academy, year, "내반", ClassType.FIXED, me);
        ClassMaster otherClass = new ClassMaster(academy, year, "남의반", ClassType.FIXED, other);
        em.persist(myClass);
        em.persist(otherClass);

        mine = enroll("내학생", "2026-0001", myClass);
        others = enroll("남의학생", "2026-0002", otherClass);

        Account account = Account.forTeacher(me, "pm-teacher", "x", false);
        em.persist(account);
        em.flush();

        teacher = AuthPrincipal.of(account.getId(), "TEACHER", academy.getId(),
                List.of(Role.TEACHER), false);
        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String name, String stdNo, ClassMaster clazz) {
        Student s = new Student("PM-" + stdNo, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, academy, year, stdNo, null, GradeType.N_SU);
        e.recordAdmission(LocalDate.now(clock));
        em.persist(e);
        em.persist(new ClassAssignment(academy, e, clazz, ClassType.FIXED));
        em.flush();
        return e;
    }

    private com.dlab.domain.user.repository.StudentSearchCondition scoped(AuthPrincipal who) {
        var base = com.dlab.domain.user.repository.StudentSearchCondition.none();
        var filter = homeroomScopeService.resolveStudentFilter(who, year, null);
        return filter.restricted() ? base.restrictedTo(filter.enrollmentIds()) : base;
    }

    // ── 역할 기본 메뉴 ─────────────────────────────────────

    @Test
    @DisplayName("★ 담임 사이드바에서 급식·수납이 빠진다 — 눌러 봐야 안 되는 메뉴였다")
    void teacherMenusExcludeDeskWork() {
        var codes = menuAccessService.visibleMenus(teacher).stream()
                .map(com.dlab.domain.menu.entity.Menu::getCode).toList();

        assertThat(codes).contains("student", "attendance", "class", "penalty", "consult");
        assertThat(codes).doesNotContain("billing", "meal", "staff", "branch-config");
    }

    @Test
    @DisplayName("조회 전용은 보기만 하는 메뉴가 남는다 — 급식은 조회로 열려 있어 보인다")
    void readonlyMenus() {
        Account account = Account.forEmployee(new Employee(academy, "조회쌤"), "pm-viewer", "x", false);
        em.persist(account.getEmployee());
        em.persist(account);
        em.flush();
        AuthPrincipal viewer = AuthPrincipal.of(account.getId(), "EMPLOYEE", academy.getId(),
                List.of(Role.READONLY), false);

        var codes = menuAccessService.visibleMenus(viewer).stream()
                .map(com.dlab.domain.menu.entity.Menu::getCode).toList();

        assertThat(codes).contains("student", "attendance", "billing", "meal", "grade");
        // 담임 업무·설정은 조회 전용에 없다
        assertThat(codes).doesNotContain("class", "penalty", "consult", "master", "staff");
    }

    @Test
    @DisplayName("★ 계정별 설정이 역할 기본값을 덮는다 — 0914 확정(계정 단위)이 깨지지 않는다")
    void accountSettingWins() {
        var menu = menuRepository.findByCodes(List.of("billing")).get(0);
        em.persist(new com.dlab.domain.menu.entity.AccountMenu(
                accountRepository.findById(teacher.accountId()).orElseThrow(), menu));
        em.flush();

        var codes = menuAccessService.visibleMenus(teacher).stream()
                .map(com.dlab.domain.menu.entity.Menu::getCode).toList();

        // 역할 기본값에는 없던 메뉴인데, 계정에 지정했으므로 그것만 보인다
        assertThat(codes).containsExactly("billing");
    }

    @Test
    @DisplayName("★ 담임 학생 목록은 맡은 학생만 나온다 — 건수까지 맞아야 한다")
    void teacherListIsScoped() {
        var page = studentService.search(SearchScope.of(teacher, (int) year, academy.getId()),
                scoped(teacher), PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(StudentEnrollment::getId)
                .containsExactly(mine.getId());
        // ★ 페이징 뒤에 걸러내면 여기서 2가 나온다 — 화면이 "2건 중 1건"으로 빈 페이지를 그린다
        assertThat(page.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("지점 관리자는 지점 전체를 본다 — 담임 범위가 걸리지 않는다")
    void adminSeesWholeBranch() {
        var page = studentService.search(SearchScope.of(admin, (int) year, academy.getId()),
                scoped(admin), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 담임에게 남의 반 학생은 '없다'로 답한다 — 403이면 존재 사실이 새어나간다")
    void otherClassStudentLooksMissing() {
        var filter = homeroomScopeService.resolveStudentFilter(teacher, year, null);

        assertThat(filter.matches(mine.getId())).isTrue();
        assertThat(filter.matches(others.getId())).isFalse();
    }

    @Test
    @DisplayName("맡은 반이 없는 담임은 아무 학생도 못 본다 — 제한 없음과 섞으면 전체가 열린다")
    void teacherWithoutClassSeesNothing() {
        Teacher lonely = new Teacher(academy, "반없는담임", null);
        em.persist(lonely);
        Account account = Account.forTeacher(lonely, "pm-lonely", "x", false);
        em.persist(account);
        em.flush();

        AuthPrincipal principal = AuthPrincipal.of(account.getId(), "TEACHER", academy.getId(),
                List.of(Role.TEACHER), false);

        var page = studentService.search(SearchScope.of(principal, (int) year, academy.getId()),
                scoped(principal), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isZero();
    }
}
