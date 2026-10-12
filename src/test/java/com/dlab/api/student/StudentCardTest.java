package com.dlab.api.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출결 카드 발급·재발급.
 *
 * <p>출결이 카드 태깅으로 돌아가는데 <b>학생에게 카드번호를 넣을 경로가 없었다</b> —
 * 직원 카드와 전년도 복사에만 있었다. 앱 QR 이 담는 값도 이 카드번호다.
 *
 * <p>지키려는 것 — <b>같은 카드가 두 학생에게 붙지 않을 것</b>. 붙으면 키오스크가 태깅할 때
 * 그 자리에서 터진다.
 */
@SpringBootTest
@Transactional
class StudentCardTest {

    @Autowired StudentService studentService;
    @Autowired StudentEnrollmentRepository enrollmentRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment first;
    StudentEnrollment second;
    AuthPrincipal admin;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) LocalDate.now(clock).getYear();
        academy = new Academy("CD01", "카드테스트지점", LocalTime.of(9, 0));
        em.persist(academy);

        first = enroll("카드학생1", "2026-0001");
        second = enroll("카드학생2", "2026-0002");

        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
        em.flush();
    }

    private StudentEnrollment enroll(String name, String studentNo) {
        Student s = new Student("CD-" + studentNo, name, "010-0000-0000");
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, academy, year, studentNo, null,
                GradeType.N_SU);
        e.recordAdmission(LocalDate.now(clock));
        em.persist(e);
        return e;
    }

    @Test
    @DisplayName("카드를 발급하면 태깅 조회에 걸린다 — 이게 없으면 앱 QR 도 만들 수 없다")
    void assignsCard() {
        studentService.changeCard(admin, first.getId(), "RFCD000001");
        em.flush();

        assertThat(first.getRfidNo()).isEqualTo("RFCD000001");
        assertThat(enrollmentRepository.findCurrentByRfidNo("RFCD000001"))
                .get().extracting(StudentEnrollment::getId).isEqualTo(first.getId());
    }

    @Test
    @DisplayName("★★ 현재 유효한 다른 학생의 카드는 거부된다 — 둘이면 태깅이 그 자리에서 터진다")
    void rejectsCardInUse() {
        studentService.changeCard(admin, first.getId(), "RFCD000001");
        em.flush();

        assertThatThrownBy(() -> studentService.changeCard(admin, second.getId(), "RFCD000001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("2026-0001");
    }

    @Test
    @DisplayName("자기 카드를 다시 넣는 것은 막지 않는다 — 저장을 두 번 누른 것일 뿐이다")
    void samePersonCanRepeat() {
        studentService.changeCard(admin, first.getId(), "RFCD000001");
        em.flush();

        studentService.changeCard(admin, first.getId(), "RFCD000001");
        assertThat(first.getRfidNo()).isEqualTo("RFCD000001");
    }

    @Test
    @DisplayName("비워 보내면 카드가 해제된다 — 분실 신고 후 재발급 전 상태")
    void clearsCard() {
        studentService.changeCard(admin, first.getId(), "RFCD000001");
        em.flush();

        studentService.changeCard(admin, first.getId(), "  ");
        em.flush();

        assertThat(first.getRfidNo()).isNull();
        // 해제했으면 그 카드를 다른 학생이 쓸 수 있어야 한다
        studentService.changeCard(admin, second.getId(), "RFCD000001");
        assertThat(second.getRfidNo()).isEqualTo("RFCD000001");
    }

    @Test
    @DisplayName("다른 지점 학생의 카드는 건드릴 수 없다")
    void cannotTouchOtherBranch() {
        Academy other = new Academy("CD02", "다른지점", LocalTime.of(9, 0));
        em.persist(other);
        Student s = new Student("CD-OTHER", "남의학생", "010-0000-0001");
        em.persist(s);
        StudentEnrollment otherEnrollment = new StudentEnrollment(
                s, other, year, "2026-0003", null, GradeType.N_SU);
        em.persist(otherEnrollment);
        em.flush();

        assertThatThrownBy(() ->
                studentService.changeCard(admin, otherEnrollment.getId(), "RFCD000009"))
                .isInstanceOf(BusinessException.class);
    }
}
