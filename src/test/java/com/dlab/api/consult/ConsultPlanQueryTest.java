package com.dlab.api.consult;

import com.dlab.domain.consult.entity.ConsultLog;
import com.dlab.domain.consult.entity.ConsultMethod;
import com.dlab.domain.consult.entity.ConsultType;
import com.dlab.domain.consult.entity.ParentShare;
import com.dlab.domain.consult.service.ConsultPlanQueryService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 앱 — 지난 상담의 「함께 정한 계획」.
 *
 * <p>지키려는 것 — <b>상담 내용은 나가지 않을 것</b>, <b>학부모는 담임이 공개한 상담만 볼 것</b>.
 */
@SpringBootTest
@Transactional
class ConsultPlanQueryTest {

    @Autowired ConsultPlanQueryService service;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    StudentEnrollment student;
    Teacher teacher;

    @BeforeEach
    void setUp() {
        Academy bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        teacher = new Teacher(bundang, "김담임", "010-0000-1111");
        em.persist(teacher);
        Student s = new Student("DL-CP1", "상담학생", "010-2222-3333");
        em.persist(s);
        student = new StudentEnrollment(s, bundang, (short) 2089, "2089-0001", null, GradeType.N_SU);
        em.persist(student);
        em.flush();
    }

    private void log(String content, String plan, ParentShare share) {
        ConsultLog log = new ConsultLog(student, teacher, ConsultType.REGULAR,
                ConsultMethod.FACE, LocalDate.of(2089, 5, 1), content);
        log.update(ConsultType.REGULAR, ConsultMethod.FACE, LocalDate.of(2089, 5, 1),
                null, content, plan, false, LocalDate.of(2089, 5, 15), share, null);
        em.persist(log);
        em.flush();
    }

    @Test
    @DisplayName("★ 계획만 나온다 — 상담 내용은 담임이 학생과 나눈 이야기라 앱에 나가지 않는다")
    void returnsPlanOnly() {
        log("가정 문제로 힘들어함", "수학 오답노트 매일 1장", ParentShare.NONE);

        var plans = service.plans(student, false);

        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).actionPlan()).isEqualTo("수학 오답노트 매일 1장");
        assertThat(plans.get(0).teacherName()).isEqualTo("김담임");
    }

    @Test
    @DisplayName("★★ 학부모는 담임이 공개로 지정한 상담만 본다 — 기본값은 비공개다")
    void parentSeesSharedOnly() {
        log("비공개 상담", "비공개 계획", ParentShare.NONE);
        log("공개 상담", "공개 계획", ParentShare.SUMMARY);

        assertThat(service.plans(student, true))
                .extracting(ConsultPlanQueryService.Plan::actionPlan)
                .containsExactly("공개 계획");
        // 학생 본인은 둘 다 본다 — 자기와 정한 계획이다
        assertThat(service.plans(student, false)).hasSize(2);
    }

    @Test
    @DisplayName("계획이 없는 상담은 빠진다 — 빈 카드가 뜨면 안 된다")
    void skipsLogsWithoutPlan() {
        log("잡담", null, ParentShare.FULL);

        assertThat(service.plans(student, false)).isEmpty();
    }
}
