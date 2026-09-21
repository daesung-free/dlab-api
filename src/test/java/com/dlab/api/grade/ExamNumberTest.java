package com.dlab.api.grade;

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
    @DisplayName("반에 모의고사 번호가 없으면 채번하지 않는다 — 틀린 번호보다 없는 편이 낫다")
    void skipsWhenClassHasNoExamNo() {
        ClassMaster noExamNo = new ClassMaster(bundang, YEAR, "특별반", ClassType.FIXED, null);
        em.persist(noExamNo);
        em.flush();

        StudentEnrollment student = student("무번호", YEAR + "-0007");
        classService.assignStudent(noExamNo.getId(), student.getId(), admin);
        em.flush();

        assertThat(student.getExamStudentNo()).isNull();
    }
}
