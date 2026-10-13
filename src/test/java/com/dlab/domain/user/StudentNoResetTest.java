package com.dlab.domain.user;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.service.ClassService;
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
 * <h2>★★ 2026-10-13 피드백 1-1 로 채번 구조가 바뀌었다</h2>
 * 전에는 <b>접수 시점에 연도별 일련번호</b>(`2026-0001`)를 발급했다. 지금은 디랩 요구대로
 * <b>반 배정 시점에 반별 4자리</b>로 발급한다 — 「신규생 접수 → 행정직원이 반배정 →
 * <b>학번 자동부여</b>」.
 *
 * <p>그래서 이 클래스가 고정하는 것이 바뀌었다.
 * <ol>
 *   <li><b>★ 접수만으로는 학번이 없다</b> — 번호가 반에 종속되어 반을 모르면 만들 수 없다</li>
 *   <li><b>★ 반 배정 시 발급된다</b>(반 1~2자리 + 순번, 전체 4자리)</li>
 *   <li><b>매년 초기화는 그대로 성립</b>한다 — 반이 해마다 새로 만들어지고 순번도 1부터다</li>
 *   <li><b>지점이 다르면 겹친다</b>는 것도 그대로다 — 학번만으로 학생을 식별하면 안 된다</li>
 * </ol>
 *
 * <p>⚠️ <b>0 이나 임시값을 넣지 않는다.</b> 그 값이 키패드 출결·성적 매칭에 섞인다 —
 * 비어 있는 것이 「아직 배정 전」의 정직한 표현이다.
 */
@SpringBootTest
@Transactional
class StudentNoResetTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired StudentService studentService;
    @Autowired ClassService classService;
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
                target.getId(), year, name, null, GradeType.N_SU, null, null,
                null, null, null, null, null, principal);
        em.flush();
        return enrollment;
    }

    @Test
    @DisplayName("★★ 접수만으로는 학번이 없다 — 번호가 반에 종속되어 반을 모르면 만들 수 없다")
    void noStudentNoBeforeClassAssignment() {
        StudentEnrollment admitted = admit(academy, (short) 2026, "접수만한학생");

        // ⚠️ 0 이나 임시값을 넣지 않는다 — 그 값이 키패드 출결·성적 매칭에 섞인다
        assertThat(admitted.getStudentNo()).isNull();
    }

    @Test
    @DisplayName("★★ 반 배정 시 반별 4자리로 발급된다 — 학번과 수험번호가 같은 값이다")
    void assignedOnClassAssignment() {
        StudentEnrollment student = admit(academy, (short) 2026, "배정받을학생");
        ClassMaster class1 = classWithExamNo(academy, (short) 2026, "1반", (short) 1);

        classService.assignStudent(class1.getId(), student.getId(), principal);
        em.flush();

        // 1반 1번 → 1001. 두 벌로 들면 「1001」이 두 뜻을 갖는다
        assertThat(student.getStudentNo()).isEqualTo("1001");
        assertThat(student.getExamStudentNo()).isEqualTo("1001");
    }

    @Test
    @DisplayName("★ 같은 반 안에서 순번이 1부터 증가한다")
    void sequenceIncreasesWithinClass() {
        ClassMaster class1 = classWithExamNo(academy, (short) 2026, "1반", (short) 1);

        assertThat(assignTo(class1, "첫번째", (short) 2026)).isEqualTo("1001");
        assertThat(assignTo(class1, "두번째", (short) 2026)).isEqualTo("1002");
        assertThat(assignTo(class1, "세번째", (short) 2026)).isEqualTo("1003");
    }

    @Test
    @DisplayName("★ 반이 다르면 앞자리가 다르다 — 11반은 반 2자리 + 순번 2자리다")
    void classPrefixDiffers() {
        ClassMaster class1 = classWithExamNo(academy, (short) 2026, "1반", (short) 1);
        ClassMaster class11 = classWithExamNo(academy, (short) 2026, "11반", (short) 11);

        assertThat(assignTo(class1, "1반생", (short) 2026)).isEqualTo("1001");
        // 전체가 늘 4자리다 — 전에는 11001(5자리)이라 더프 제출번호가 비었다
        assertThat(assignTo(class11, "11반생", (short) 2026)).isEqualTo("1101");
    }

    @Test
    @DisplayName("★ 연도가 바뀌면 1번부터 다시 시작한다 — 이게 '매년 초기화'다")
    void resetsEachYear() {
        ClassMaster y2026 = classWithExamNo(academy, (short) 2026, "1반", (short) 1);
        ClassMaster y2027 = classWithExamNo(academy, (short) 2027, "1반", (short) 1);

        assertThat(assignTo(y2026, "올해첫번째", (short) 2026)).isEqualTo("1001");
        assertThat(assignTo(y2026, "올해두번째", (short) 2026)).isEqualTo("1002");
        // 반이 해마다 새로 만들어지고 순번도 1부터다
        assertThat(assignTo(y2027, "내년첫번째", (short) 2027)).isEqualTo("1001");
    }

    @Test
    @DisplayName("★ 지점이 다르면 학번이 겹친다 — 학번만으로 학생을 식별하면 안 된다")
    void collidesAcrossBranches() {
        ClassMaster mine = classWithExamNo(academy, (short) 2026, "1반", (short) 1);
        ClassMaster theirs = classWithExamNo(otherAcademy, (short) 2026, "1반", (short) 1);

        assertThat(assignTo(mine, "우리지점", (short) 2026)).isEqualTo("1001");
        assertThat(assignTo(theirs, "다른지점", (short) 2026)).isEqualTo("1001");
    }

    @Test
    @DisplayName("반에 수험번호 반이 없으면 학번도 안 나간다 — 틀린 번호보다 없는 편이 낫다")
    void noNumberWhenClassHasNoExamNo() {
        StudentEnrollment student = admit(academy, (short) 2026, "번호없는반학생");
        ClassMaster noExamNo = new ClassMaster(academy, (short) 2026, "특별반",
                ClassType.FIXED, null);
        em.persist(noExamNo);
        em.flush();

        classService.assignStudent(noExamNo.getId(), student.getId(), principal);
        em.flush();

        assertThat(student.getStudentNo()).isNull();
    }

    @Test
    @DisplayName("★ 반을 옮겨도 학번은 그대로다 — 학생이 외우고 카드·출결에 쓰는 값이다")
    void keepsNumberWhenMovingClass() {
        ClassMaster class1 = classWithExamNo(academy, (short) 2026, "1반", (short) 1);
        ClassMaster class2 = classWithExamNo(academy, (short) 2026, "2반", (short) 2);

        StudentEnrollment student = admit(academy, (short) 2026, "옮기는학생");
        classService.assignStudent(class1.getId(), student.getId(), principal);
        em.flush();
        String first = student.getStudentNo();

        classService.assignStudent(class2.getId(), student.getId(), principal);
        em.flush();

        // 번호가 반에서 나오지만 **한 번 받으면 바뀌지 않는다** —
        // 바뀌면 지난 회차 성적·상담과의 연결이 끊긴다
        assertThat(student.getStudentNo()).isEqualTo(first);
    }

    // ── 픽스처 ───────────────────────────────────────────

    private ClassMaster classWithExamNo(Academy target, short year, String name, short examNo) {
        ClassMaster classMaster = new ClassMaster(target, year, name, ClassType.FIXED, null);
        classMaster.changeExamClassNo(examNo);
        em.persist(classMaster);
        em.flush();
        return classMaster;
    }

    private String assignTo(ClassMaster classMaster, String name, short year) {
        StudentEnrollment student = admit(classMaster.getAcademy(), year, name);
        classService.assignStudent(classMaster.getId(), student.getId(), principal);
        em.flush();
        return student.getStudentNo();
    }
}
