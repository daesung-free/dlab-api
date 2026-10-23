package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.entity.VocabMeaning;
import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.entity.VocabWord;
import com.dlab.domain.vocab.service.VocabExamService;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 응시 API 쿼리 수 (v1 9장 부하 요건).
 *
 * <h2>★★ 부하 테스트 전에 쿼리 수를 고정한다</h2>
 * v1 9장이 <i>"최대 50%가 동시 접속할 수 있다(약 2,500명). <b>시험 시작과 제출 API에
 * 부하 테스트를 한다</b>"</i>고 했다. 그런데 <b>실제 부하를 걸기 전에 먼저 할 일이
 * 이것</b>이다 — 문항 하나마다 쿼리가 하나 늘어나는 구조면, 서버를 키워도 같은 비율로
 * 늘어난다.
 *
 * <p>★ <b>그리고 이 테스트가 없으면 나중에 다시 느려진다.</b> 누가 보기 추출에 조회를
 * 하나 더 넣어도 기능은 그대로 동작하고 테스트도 통과한다 — <b>느려지는 것만 아무도
 * 모른다.</b> 숫자를 박아 두면 CI 가 잡는다.
 *
 * <h2>상한을 「지금 값」이 아니라 「넘으면 안 되는 값」으로 둔다</h2>
 * 정확히 같은 수로 고정하면 무해한 리팩터링마다 테스트를 고치게 되고, 그러다 숫자를
 * 올리는 것이 습관이 된다. <b>문항 수에 비례해 늘어나지 않는다</b>는 것만 지킨다.
 */
@SpringBootTest
@Transactional
class VocabExamQueryCountTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 2);

    @Autowired VocabExamService examService;
    @Autowired EntityManager em;
    @Autowired SessionFactory sessionFactory;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    StudentEnrollment enrollment;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) START.getYear();
        academy = new Academy("VQ01", "쿼리지점", LocalTime.of(9, 0));
        em.persist(academy);

        // 50유닛 × 40단어가 실제 규모다(v2 6.2). 테스트는 5유닛 × 40단어로 줄인다 —
        // ★ 쿼리 수는 단어 수가 아니라 문항 수에 걸리므로 비례 관계는 그대로 드러난다
        for (short unit = 1; unit <= 5; unit++) {
            for (int i = 1; i <= 40; i++) {
                word(unit, unit * 100 + i, "w%d_%d".formatted(unit, i), "뜻%d_%d".formatted(unit, i));
            }
        }
        var person = new Student("VQ-STD", "쿼리학생", "010-0000-0001");
        em.persist(person);
        enrollment = new StudentEnrollment(person, academy, year,
                "VQ-0001", null, GradeType.N_SU);
        em.persist(enrollment);
        em.flush();
    }

    private void word(short unit, int seq, String text, String meaning) {
        var w = new VocabWord(unit, "%05d".formatted(seq), text, meaning);
        w.overwrite(unit, text, null, "n", meaning, null, null, null, null, null, null, null);
        em.persist(w);
        em.persist(new VocabMeaning(w, meaning, VocabMeaning.Origin.SPLIT));
    }

    private VocabScheduleConfig config(short questionCount, short mcRate) {
        var c = new VocabScheduleConfig(academy, year, START);
        c.modify(null, questionCount, questionCount, (short) 4, mcRate, false, true);
        em.persist(c);
        return c;
    }

    /**
     * 회차 하나.
     *
     * <p>⚠️ 날짜를 받는다 — {@code uq_vocab_exam_round} 가 (지점, 연도, 공개일, 누적여부)다.
     * 같은 날짜로 둘을 만들면 그 제약에 걸린다(공개 멱등성을 지키는 제약이라 올바르다).
     */
    private VocabExamRound round(int dayOffset, short from, short to) {
        var r = new VocabExamRound(academy, year, dayOffset + 1, (short) 1,
                START.plusDays(dayOffset), false, from, to);
        em.persist(r);
        return r;
    }

    /** 그 작업이 실제로 보낸 쿼리 수. */
    private long countQueries(Runnable work) {
        em.flush();
        em.clear();
        Statistics stats = sessionFactory.getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            work.run();
            em.flush();
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    @DisplayName("★★ 문항이 5배로 늘어도 쿼리가 5배로 늘지 않는다 — 2,500명 동시 접속의 관건이다")
    void startDoesNotScaleWithQuestionCount() {
        config((short) 4, (short) 100);   // 전부 객관식 — 보기 추출이 도는 쪽이다
        var small = round(0, (short) 1, (short) 1);
        em.flush();

        long fourQuestions = countQueries(() ->
                examService.start(enrollment.getId(), small.getId()));

        // 같은 지점에 문항 20개짜리 회차를 하나 더 둔다
        em.createQuery("update VocabScheduleConfig c set c.questionCount = 20, "
                        + "c.weeklyQuestionCap = 20 where c.academy.id = :a")
                .setParameter("a", academy.getId())
                .executeUpdate();
        var big = round(1, (short) 2, (short) 2);
        em.flush();

        long twentyQuestions = countQueries(() ->
                examService.start(enrollment.getId(), big.getId()));

        // ★★ 상한이 아니라 「기울기」를 본다.
        //
        //   고정 상한을 두면 무해한 리팩터링마다 숫자를 올리게 되고, 그러다 올리는 것이
        //   습관이 된다. 지켜야 하는 성질은 **문항이 하나 늘 때 쿼리가 몇 개 느는가**다.
        //
        //   답안 한 줄은 원장이라 INSERT 가 하나씩 늘어나는 것이 당연하다(피할 수 없다).
        //   거기에 **조회가 하나라도 섞이면 기울기가 2가 되고**, 2,500명 동시 접속에서
        //   그 차이가 그대로 배로 늘어난다.
        long slope = (twentyQuestions - fourQuestions) / (20 - 4);
        assertThat(slope)
                .as("문항 4개 %d 쿼리 → 20개 %d 쿼리 (문항당 %d). "
                        + "★ 1이어야 한다 — 답안 INSERT 하나뿐이다. 2 면 보기 추출이 "
                        + "문항마다 조회를 돌리고 있다",
                        fourQuestions, twentyQuestions, slope)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 범위가 5유닛이어도(3회독) 유닛마다 보기 조회가 돌지 않는다")
    void startDoesNotScaleWithRangeWidth() {
        config((short) 10, (short) 100);
        var oneUnit = round(0, (short) 1, (short) 1);
        em.flush();

        long narrow = countQueries(() ->
                examService.start(enrollment.getId(), oneUnit.getId()));

        var fiveUnits = new VocabExamRound(academy, year, 2, (short) 3,
                START.plusDays(1), false, (short) 1, (short) 5);
        em.persist(fiveUnits);
        em.flush();

        long wide = countQueries(() ->
                examService.start(enrollment.getId(), fiveUnits.getId()));

        // 3회독은 하루 5유닛이다(v1 5.3). 범위 조회가 유닛마다 도는 것은 받아들이지만
        // (5번) 보기 추출까지 겹치면 곱해진다
        assertThat(wide)
                .as("1유닛 %d 쿼리 → 5유닛 %d 쿼리", narrow, wide)
                .isLessThan(narrow + 20);
    }

    @Test
    @DisplayName("★★ 제출 채점이 문항마다 정답 후보를 조회하지 않는다 — 제출이 가장 몰리는 시점이다")
    void submitDoesNotScaleWithQuestionCount() {
        config((short) 4, (short) 0);   // 전부 주관식 — 정답 후보 조회가 도는 쪽이다
        var small = round(0, (short) 1, (short) 1);
        em.flush();

        var started4 = examService.start(enrollment.getId(), small.getId());
        Map<Short, String> given4 = answersOf(started4);
        long four = countQueries(() ->
                examService.submit(enrollment.getId(), started4.attemptId(), given4));

        em.createQuery("update VocabScheduleConfig c set c.questionCount = 20 "
                        + "where c.academy.id = :a")
                .setParameter("a", academy.getId())
                .executeUpdate();
        var big = round(1, (short) 2, (short) 2);
        em.flush();

        var started20 = examService.start(enrollment.getId(), big.getId());
        Map<Short, String> given20 = answersOf(started20);
        long twenty = countQueries(() ->
                examService.submit(enrollment.getId(), started20.attemptId(), given20));

        // ★★ 여기서도 기울기를 본다. 채점은 답안 한 줄에 UPDATE 하나가 당연하고
        //   (학생 답 + 정오를 적는다), 정답 후보 조회가 섞이면 2가 된다.
        //
        //   ★ 제출이 start 보다 위험하다 — 시험이 끝나는 순간 한꺼번에 몰린다.
        long slope = (twenty - four) / (20 - 4);
        assertThat(slope)
                .as("문항 4개 %d 쿼리 → 20개 %d 쿼리 (문항당 %d). "
                        + "★ 1이어야 한다 — 채점 UPDATE 하나뿐이다. 2 면 채점이 "
                        + "단어마다 정답 후보를 조회하고 있다", four, twenty, slope)
                .isEqualTo(1);
    }

    private Map<Short, String> answersOf(VocabExamService.StartResult started) {
        Map<Short, String> given = new HashMap<>();
        for (var q : started.questions()) {
            given.put(q.seqNo(), "답");
        }
        return given;
    }
}
