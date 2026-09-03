package com.dlab.domain.user;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.service.StudentService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 학번 채번 · 연도 전환 (실행가이드 P1-13 "학번 초기화", F-4.1-4).
 *
 * <p><b>학번 초기화는 별도 기능이 아니라 채번 구조가 보장하는 성질이다.</b>
 * 채번이 {@code (지점, 연도)} 범위의 최대값 + 1이라 <b>연도가 바뀌면 자동으로 1번부터</b> 시작한다.
 * 실행가이드 체크 항목도 *"학번채번 정책 재확인(연도전환 엣지케이스)"* 이라 만드는 게 아니라 확인이다.
 *
 * <p>그래서 이 클래스는 <b>그 성질이 실제로 성립하는지 고정</b>한다 — 채번 로직을 나중에
 * 손댈 때(예: 지점 무관 전역 채번으로 바꾸려 할 때) 여기서 걸린다.
 */
@SpringBootTest
@Transactional
class StudentNoResetTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired StudentService studentService;
    @Autowired AcademyRepository academyRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    Academy otherAcademy;
    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        academy = academyRepository.save(new Academy(
                "SN" + SEQ.incrementAndGet() + System.nanoTime() % 10_000,
                "학번테스트지점", LocalTime.of(9, 0)));
        otherAcademy = academyRepository.save(new Academy(
                "SO" + SEQ.incrementAndGet() + System.nanoTime() % 10_000,
                "학번테스트지점2", LocalTime.of(9, 0)));
        em.flush();
        principal = AuthPrincipal.of(0L, "EMPLOYEE", academy.getId(),
                List.of(Role.SUPER_ADMIN), true);
    }

    private StudentEnrollment admit(Academy target, short year, String name) {
        StudentEnrollment enrollment = studentService.admit(
                target.getId(), year, name, null, GradeType.N_SU, null,
                null, null, null, null, null, principal);
        em.flush();
        return enrollment;
    }

    @Test
    @DisplayName("★ 연도가 바뀌면 학번이 1번부터 다시 시작한다 — 이게 '매년 초기화'다")
    void studentNoRestartsEachYear() {
        assertThat(admit(academy, (short) 2026, "첫해첫번째").getStudentNo()).isEqualTo("2026-0001");
        assertThat(admit(academy, (short) 2026, "첫해두번째").getStudentNo()).isEqualTo("2026-0002");

        // 다음 해 — 2026-0002 다음이 2027-0003이 되면 안 된다
        assertThat(admit(academy, (short) 2027, "다음해첫번째").getStudentNo()).isEqualTo("2027-0001");
    }

    @Test
    @DisplayName("★ 지점이 다르면 학번이 겹친다 — 학번만으로 학생을 식별하면 안 된다")
    void studentNoIsScopedToAcademy() {
        String mine = admit(academy, (short) 2026, "우리지점").getStudentNo();
        String theirs = admit(otherAcademy, (short) 2026, "다른지점").getStudentNo();

        // 유니크 제약이 (academy_id, year, student_no)라 지점 간에는 같은 번호가 나온다.
        // 학번을 PK나 외부 연동 키로 쓰면 여기서 깨진다.
        assertThat(mine).isEqualTo("2026-0001");
        assertThat(theirs).isEqualTo("2026-0001");
    }

    @Test
    @DisplayName("이전 연도에 등록 건이 있어도 새 연도 채번에 영향을 주지 않는다")
    void previousYearDoesNotLeak() {
        for (int i = 0; i < 5; i++) {
            admit(academy, (short) 2026, "이전해" + i);
        }
        assertThat(admit(academy, (short) 2027, "새해").getStudentNo()).isEqualTo("2027-0001");
    }

    @Test
    @DisplayName("학번 형식은 yyyy-NNNN 4자리다 — 자릿수가 바뀌면 정렬·파싱이 깨진다")
    void studentNoFormat() {
        assertThat(admit(academy, (short) 2026, "형식확인").getStudentNo())
                .matches("^\\d{4}-\\d{4}$");
    }
}
