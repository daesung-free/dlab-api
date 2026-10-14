package com.dlab.api.grade;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.grade.repository.MockExamStudentKeyRepository;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.service.ClassService;
import jakarta.persistence.EntityManager;
import java.time.LocalTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 모의고사 수험번호 채번 (2026-09-21 답변서).
 *
 * <p>지키려는 것 — <b>반이 바뀌어도 번호가 안 바뀔 것</b>(과거 회차 성적이 그 번호로
 * 들어와 있다), <b>번호가 겹치지 않을 것</b>, <b>채번과 동시에 업로드 키가 생길 것</b>.
 */
@SpringBootTest
@Transactional
class ExamNumberTest {

    @Autowired ClassService classService;
    @Autowired MockExamStudentKeyRepository keyRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2092;

    Academy bundang;
    ClassMaster class1;
    ClassMaster class2;
    AuthPrincipal admin;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        em.flush();
        // 마이그레이션이 채우는 값 — 테스트는 지점을 직접 만들어 비어 있다
        em.createNativeQuery("UPDATE academy SET exam_school_cd = '99700' WHERE id = :id")
                .setParameter("id", bundang.getId()).executeUpdate();

        class1 = new ClassMaster(bundang, YEAR, "N수 1반", ClassType.FIXED, null);
        class2 = new ClassMaster(bundang, YEAR, "N수 2반", ClassType.FIXED, null);
        class1.changeExamClassNo((short) 1);
        class2.changeExamClassNo((short) 2);
        em.persist(class1);
        em.persist(class2);
        em.flush();
        em.clear();

        admin = new AuthPrincipal(1L, "admin", bundang.getId(),
                Set.of(Role.SUPER_ADMIN), true, false);
    }

    private StudentEnrollment student(String name, String no) {
        Student student = new Student("DL-" + no, name, "010-0000-0000");
        em.persist(student);
        StudentEnrollment enrollment =
                new StudentEnrollment(student, em.find(Academy.class, bundang.getId()),
                        YEAR, no, null, GradeType.N_SU);
        em.persist(enrollment);
        em.flush();
        return enrollment;
    }

    @Test
    @DisplayName("★ 반 배정 시 수험번호가 붙는다 — 반 번호 + 3자리 순번")
    void assignsOnClassAssignment() {
        StudentEnrollment first = student("김일번", YEAR + "-0001");
        StudentEnrollment second = student("이이번", YEAR + "-0002");

        classService.assignStudent(class1.getId(), first.getId(), admin);
        classService.assignStudent(class1.getId(), second.getId(), admin);
        em.flush();

        assertThat(first.getExamStudentNo()).isEqualTo("1001");
        assertThat(second.getExamStudentNo()).isEqualTo("1002");
    }

    @Test
    @DisplayName("★★ 반을 옮겨도 번호는 그대로다 — 과거 회차 성적이 그 번호로 들어와 있다")
    void numberSurvivesClassChange() {
        StudentEnrollment student = student("박이동", YEAR + "-0003");
        classService.assignStudent(class1.getId(), student.getId(), admin);
        em.flush();
        String before = student.getExamStudentNo();

        classService.assignStudent(class2.getId(), student.getId(), admin);
        em.flush();

        assertThat(student.getExamStudentNo()).isEqualTo(before);
        assertThat(student.getExamClassNo()).isEqualTo((short) 1);
    }

    @Test
    @DisplayName("★ 옮겨 간 학생의 번호를 재사용하지 않는다 — 같은 번호가 둘이 된다")
    void doesNotReuseSequence() {
        StudentEnrollment moved = student("박이동", YEAR + "-0004");
        classService.assignStudent(class1.getId(), moved.getId(), admin);
        classService.assignStudent(class2.getId(), moved.getId(), admin);
        em.flush();

        StudentEnrollment next = student("최다음", YEAR + "-0005");
        classService.assignStudent(class1.getId(), next.getId(), admin);
        em.flush();

        // 1001 은 옮겨 간 학생이 계속 들고 있다
        assertThat(next.getExamStudentNo()).isEqualTo("1002");
    }

    @Test
    @DisplayName("★★ 채번과 동시에 업로드 키가 생긴다 — 동명이인이 매번 빠지던 문제가 사라진다")
    void createsUploadKey() {
        StudentEnrollment student = student("김동명", YEAR + "-0006");
        classService.assignStudent(class1.getId(), student.getId(), admin);
        em.flush();

        var key = keyRepository.findByKey(bundang.getId(), YEAR, "99700", "1", "1001");

        assertThat(key).isPresent();
        assertThat(key.get().getEnrollment().getId()).isEqualTo(student.getId());
    }

    @Test
    @DisplayName("★ 더프 제출용은 6자리로 변환해 내보낸다 — 저장 형식은 그대로 둔다")
    void submitNoIsSixDigits() {
        StudentEnrollment student = student("김제출", YEAR + "-0008");
        classService.assignStudent(class1.getId(), student.getId(), admin);
        em.flush();

        // 저장은 (반, 순번) — 회차 자료가 이 형식으로 들어온다
        assertThat(student.getExamStudentNo()).isEqualTo("1001");
        // 제출은 [반 2자리][번호 4자리]
        assertThat(student.examSubmitNo()).isEqualTo("011001");
    }

    @Test
    @DisplayName("★★ 11~19반도 4자리다 — 전에 11001(5자리)이라 대구점 제출번호가 비어 있었다")
    void eleventhClassFitsInFourDigits() {
        ClassMaster eleventh = new ClassMaster(bundang, YEAR, "N수 11반", ClassType.FIXED, null);
        eleventh.changeExamClassNo((short) 11);
        em.persist(eleventh);
        em.flush();

        StudentEnrollment student = student("김십일반", YEAR + "-0009");
        classService.assignStudent(eleventh.getId(), student.getId(), admin);
        em.flush();

        // 반이 2자리면 순번이 2자리다 — 전체는 늘 4자리다
        assertThat(student.getExamStudentNo()).isEqualTo("1101");
        // 제출은 [반 2자리][학번 4자리] = 6자리
        assertThat(student.examSubmitNo()).isEqualTo("111101");
    }

    @Test
    @DisplayName("★★ 반 번호 10의 배수는 거절한다 — 10반 1번이 1반 1번과 같은 번호가 된다")
    void rejectsClassNoMultipleOfTen() {
        // 10|01 = 1001 = 1|001 → 1반 1번과 충돌한다.
        // 디랩이 10·20 을 쓰지 않는 이유가 이것이고, 30·40 도 같은 일이 난다
        assertThat(StudentEnrollment.formatExamNo((short) 10, (short) 1))
                .isEqualTo(StudentEnrollment.formatExamNo((short) 1, (short) 1));

        assertThat(StudentEnrollment.isUsableClassNo((short) 10)).isFalse();
        assertThat(StudentEnrollment.isUsableClassNo((short) 20)).isFalse();
        assertThat(StudentEnrollment.isUsableClassNo((short) 30)).isFalse();
        // 11~19·21~29 는 겹치지 않는다 — 막을 이유가 없다
        assertThat(StudentEnrollment.isUsableClassNo((short) 11)).isTrue();
        assertThat(StudentEnrollment.isUsableClassNo((short) 21)).isTrue();

        assertThatThrownBy(() -> classService.changeExamClassNo(
                class1.getId(), (short) 10, admin))
                .hasMessageContaining("10의 배수");
    }

    @Test
    @DisplayName("★ 반마다 1~99번이다 — 1반도 11반도 상한이 같다")
    void sequenceRangeIsSamePerClass() {
        assertThat(StudentEnrollment.formatExamNo((short) 1, StudentEnrollment.MAX_SEQ))
                .isEqualTo("1099");
        assertThat(StudentEnrollment.formatExamNo((short) 11, StudentEnrollment.MAX_SEQ))
                .isEqualTo("1199");
    }

    @Test
    @DisplayName("채번 전에는 제출번호도 없다")
    void noSubmitNoBeforeAssignment() {
        StudentEnrollment student = student("김미배정", YEAR + "-0010");
        em.flush();

        assertThat(student.examSubmitNo()).isNull();
    }

    @Test
    @DisplayName("★★ 반에 모의고사 번호가 없으면 배정 자체를 막는다 — 조용히 넘기면 학번 없는 학생이 생긴다")
    void rejectsAssignWhenClassHasNoExamNo() {
        ClassMaster noExamNo = new ClassMaster(bundang, YEAR, "특별반", ClassType.FIXED, null);
        em.persist(noExamNo);
        em.flush();

        StudentEnrollment student = student("무번호", YEAR + "-0007");

        // ★ 전에는 **배정이 통과하고 채번만 조용히 건너뛰었다.** 그러면 화면에는
        //   「채번이 고장났다」로 보이고(2026-10-13 프론트 보고), 그 학생은 학번이 없어
        //   **키패드 출결·식사체크를 쓸 수 없다**. 나중에 붙이려면 그 반 순번을
        //   통째로 다시 정리해야 하므로, 한 번 거절하는 쪽이 싸다
        assertThatThrownBy(() ->
                classService.assignStudent(noExamNo.getId(), student.getId(), admin))
                .isInstanceOf(BusinessException.class)
                // 무엇을 해야 하는지까지 돌려준다
                .hasMessageContaining("반 관리에서 먼저 지정");

        assertThat(student.getExamStudentNo()).isNull();
    }
}
