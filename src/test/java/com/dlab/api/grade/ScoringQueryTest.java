package com.dlab.api.grade;

import com.dlab.domain.grade.entity.ExamCode;
import com.dlab.domain.grade.entity.ExamItem;
import com.dlab.domain.grade.entity.ExamMaster;
import com.dlab.domain.grade.entity.StudentItemResponse;
import com.dlab.domain.grade.service.ScoringQueryService;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 채점 탭.
 *
 * <p>지키려는 것 — <b>국어 영역이 공통+선택으로 묶일 것</b>, <b>복습 순서가 전국 정답률 높은
 * 순일 것</b>, <b>함정 오답을 짚을 것</b>, <b>내 정답률과 전국 정답률을 함께 줄 것</b>.
 */
@SpringBootTest
@Transactional
class ScoringQueryTest {

    @Autowired ScoringQueryService service;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    StudentEnrollment student;
    ExamMaster exam;

    @BeforeEach
    void setUp() {
        Academy bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);
        Student s = new Student("DL-S1", "채점학생", "010-1111-5555");
        em.persist(s);
        student = new StudentEnrollment(s, bundang, (short) 2086, "2086-0001", null, GradeType.N_SU);
        em.persist(student);
        exam = ExamMaster.academyExam(null, (short) 2086, GradeType.N_SU, ExamCode.MONTHLY,
                "8월 더 프리미엄", LocalDate.of(2086, 8, 18), 1);
        exam.addSubject("KOREAN", "국어", 1);
        em.persist(exam);

        // 국어 1(쉬움, 정답 2) · 2(어려움, 정답 5) · 3(쉬움, 정답 1) + 언매 35(정답 4)
        item("국어", 1, 2, false, "사실적 이해", "94.4", new String[]{"0.8", "94.4", "3.4", "0.7", "0.6"});
        item("국어", 2, 5, false, "추론적 이해", "40.0", new String[]{"10", "35", "5", "10", "40"});
        item("국어", 3, 1, false, "사실적 이해", "82.5", new String[]{"82.5", "5.9", "4.3", "3.7", "3.3"});
        item("언어와매체", 35, 4, true, "문법", "70.0", new String[]{"5", "10", "10", "70", "5"});
        em.flush();
    }

    private void item(String subject, int no, int answer, boolean elective, String skill,
                      String rate, String[] choices) {
        ExamItem i = new ExamItem(exam, "01", subject, (short) no, (short) answer, (short) 2,
                elective, null, "독서", null, skill);
        BigDecimal[] c = new BigDecimal[5];
        for (int k = 0; k < 5; k++) {
            c[k] = new BigDecimal(choices[k]);
        }
        i.applyRates(new BigDecimal(rate), c, null);
        em.persist(i);
    }

    private void respond(String common, List<String> commonAnswers, String elective, String electiveAnswer) {
        em.persist(new StudentItemResponse(student, exam, "국어", (short) 1, common, commonAnswers));
        em.persist(new StudentItemResponse(student, exam, "언어와매체", (short) 35, elective, List.of(electiveAnswer)));
        em.flush();
    }

    @Test
    @DisplayName("★ 국어 영역은 공통과 선택을 묶는다 — 저장은 과목별이지만 화면은 영역이다")
    void groupsCommonAndElectiveIntoArea() {
        respond("OOO", List.of("2", "5", "1"), "O", "4");

        var areas = service.scoring(student, exam.getId());

        assertThat(areas).hasSize(1);
        assertThat(areas.get(0).name()).isEqualTo("국어");
        assertThat(areas.get(0).elective()).isEqualTo("언어와매체");
        assertThat(areas.get(0).total()).isEqualTo(4);
    }

    @Test
    @DisplayName("★★ 복습 순서는 전국 정답률이 높은 순이다 — 남들은 맞혔는데 나만 틀린 문항이 먼저")
    void reviewOrderedByNationalRate() {
        respond("XXX", List.of("3", "4", "2"), "O", "4");

        var review = service.scoring(student, exam.getId()).get(0).review();

        assertThat(review).extracting(ScoringQueryService.Review::questionNo)
                .containsExactly(1, 3, 2);   // 94.4 → 82.5 → 40.0
        assertThat(service.scoring(student, exam.getId()).get(0).lostPoints()).isEqualTo(6);
    }

    @Test
    @DisplayName("★ 가장 많이 고른 오답을 골랐으면 함정으로 짚는다")
    void marksTrapAnswer() {
        // 2번 정답 5, 가장 많이 고른 오답은 2번(35%) — 2를 골랐다
        respond("OXO", List.of("2", "2", "1"), "O", "4");

        var review = service.scoring(student, exam.getId()).get(0).review();

        assertThat(review).hasSize(1);
        assertThat(review.get(0).trap()).isTrue();
    }

    @Test
    @DisplayName("함정이 아닌 오답은 짚지 않는다")
    void nonTrapWrongAnswer() {
        respond("OXO", List.of("2", "3", "1"), "O", "4");   // 2번에 3(5%) — 흔한 오답 아님

        assertThat(service.scoring(student, exam.getId()).get(0).review().get(0).trap()).isFalse();
    }

    @Test
    @DisplayName("★ 평가요소별로 내 정답률과 전국 정답률을 함께 준다 — 원래 어려운지 나만 약한지 가른다")
    void breakdownBySkill() {
        respond("OXX", List.of("2", "1", "3"), "O", "4");

        var bySkill = service.scoring(student, exam.getId()).get(0).bySkill();
        var fact = bySkill.stream().filter(b -> b.name().equals("사실적 이해")).findFirst().orElseThrow();

        assertThat(fact.questions()).isEqualTo(2);
        assertThat(fact.myRate()).isEqualByComparingTo("50.0");        // 2문항 중 1개
        assertThat(fact.nationalRate()).isEqualByComparingTo("88.5");  // (94.4 + 82.5) / 2
    }
}
