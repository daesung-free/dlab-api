package com.dlab.api.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.attendance.service.AttendanceBoardService;
import com.dlab.domain.attendance.service.AttendanceBoardService.AttendanceRow;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
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
 * 담임(TEACHER)의 출결 조회 범위 (§4 권한 4·14번).
 *
 * <p>담임 계정은 {@code /students}에서 {@code @PreAuthorize}로 막혀 있어 "권한이 닫혔다"고
 * 보기 쉬운데, <b>출결은 담임이 매일 쓰는 화면이라 열려 있다.</b> 그쪽이 지점 전체를
 * 내려주고 있었다 — 이 테스트가 그 회귀를 잡는다.
 *
 * <p>★ {@code classId}는 화면이 고르는 필터라 <b>빼고 부르면</b> 전체가 왔다.
 * 그래서 "반을 안 고른 담임"이 이 테스트의 핵심 케이스다.
 */
@SpringBootTest
@Transactional
class TeacherHomeroomScopeTest {

    @Autowired AttendanceBoardService boardService;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    LocalDate day;
    ClassMaster myClass;
    ClassMaster otherClass;
    Teacher me;
    AuthPrincipal teacher;
    AuthPrincipal branchAdmin;

    @BeforeEach
    void setUp() {
        day = LocalDate.now(clock);
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.persist(new PeriodMaster(bundang, (short) day.getYear(), (short) 1, "자습",
                DayType.of(day), PeriodType.SELF_STUDY,
                LocalTime.of(8, 0), LocalTime.of(22, 0)));

        me = new Teacher(bundang, "김담임", null);
        em.persist(me);
        Teacher other = new Teacher(bundang, "이담임", null);
        em.persist(other);

        myClass = new ClassMaster(bundang, (short) day.getYear(), "N수 1반", ClassType.FIXED, me);
        otherClass = new ClassMaster(bundang, (short) day.getYear(), "N수 2반", ClassType.FIXED, other);
        em.persist(myClass);
        em.persist(otherClass);

        Account account = Account.forTeacher(me, "teacher-scope-test", "x", false);
        em.persist(account);
        em.flush();

        teacher = AuthPrincipal.of(account.getId(), "TEACHER", bundang.getId(),
                List.of(Role.TEACHER), false);
        branchAdmin = AuthPrincipal.of(1L, "EMPLOYEE", bundang.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    private StudentEnrollment enroll(String code, String name, String stdNo, ClassMaster clazz) {
        Student student = new Student(code, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment e = new StudentEnrollment(
                student, bundang, (short) day.getYear(), stdNo, null, GradeType.N_SU);
        em.persist(e);
        if (clazz != null) {
            em.persist(new ClassAssignment(bundang, e, clazz, ClassType.FIXED));
        }
        em.flush();
        return e;
    }

    private List<String> studentNosOf(List<AttendanceRow> rows) {
        return rows.stream().map(AttendanceRow::studentNo).sorted().toList();
    }

    @Test
    @DisplayName("★ 반을 안 골라도 담임에게는 맡은 반만 나온다 — 여기가 뚫려 있었다")
    void teacherSeesOnlyHomeroomEvenWithoutClassFilter() {
        enroll("DL-1", "내반학생", "2026-0001", myClass);
        enroll("DL-2", "남의반학생", "2026-0002", otherClass);
        enroll("DL-3", "미배정학생", "2026-0003", null);

        List<AttendanceRow> rows = boardService.board(teacher, null, day, null);

        assertThat(studentNosOf(rows)).containsExactly("2026-0001");
    }

    @Test
    @DisplayName("담임이 남의 반 번호를 직접 넣어도 안 보인다")
    void teacherCannotPeekAnotherClassById() {
        enroll("DL-1", "내반학생", "2026-0001", myClass);
        enroll("DL-2", "남의반학생", "2026-0002", otherClass);

        assertThat(boardService.board(teacher, null, day, otherClass.getId())).isEmpty();
    }

    @Test
    @DisplayName("★ 맡은 반이 없는 담임은 아무것도 못 본다 — 빈 집합을 '제한 없음'으로 읽으면 정반대가 된다")
    void teacherWithoutHomeroomSeesNothing() {
        Teacher rookie = new Teacher(bundang, "신입담임", null);
        em.persist(rookie);
        Account account = Account.forTeacher(rookie, "rookie-scope-test", "x", false);
        em.persist(account);
        em.flush();
        AuthPrincipal noClassTeacher = AuthPrincipal.of(account.getId(), "TEACHER",
                bundang.getId(), List.of(Role.TEACHER), false);

        enroll("DL-1", "내반학생", "2026-0001", myClass);
        enroll("DL-2", "남의반학생", "2026-0002", otherClass);

        assertThat(boardService.board(noClassTeacher, null, day, null)).isEmpty();
    }

    @Test
    @DisplayName("지점 관리자는 그대로 전체를 본다 — 담임 제한이 다른 역할로 번지면 안 된다")
    void branchAdminIsUnaffected() {
        enroll("DL-1", "내반학생", "2026-0001", myClass);
        enroll("DL-2", "남의반학생", "2026-0002", otherClass);
        enroll("DL-3", "미배정학생", "2026-0003", null);

        assertThat(studentNosOf(boardService.board(branchAdmin, null, day, null)))
                .containsExactly("2026-0001", "2026-0002", "2026-0003");
    }

    @Test
    @DisplayName("관리자가 반을 고르면 그 반만 — 기존 필터 동작은 유지된다")
    void branchAdminClassFilterStillWorks() {
        enroll("DL-1", "내반학생", "2026-0001", myClass);
        enroll("DL-2", "남의반학생", "2026-0002", otherClass);

        assertThat(studentNosOf(boardService.board(branchAdmin, null, day, otherClass.getId())))
                .containsExactly("2026-0002");
    }
}
