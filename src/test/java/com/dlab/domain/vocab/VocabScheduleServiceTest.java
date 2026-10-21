package com.dlab.domain.vocab;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.vocab.repository.VocabExamRoundRepository;
import com.dlab.domain.vocab.service.VocabScheduleService;
import jakarta.persistence.EntityManager;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 지점 일정 (v1 5장 · 7장).
 *
 * <h2>★★ 지키려는 것은 「지난 회차가 바뀌지 않는다」다</h2>
 * v1 5.6 이 <i>"나중에 시험 없는 날을 추가해도 지난 회차와 지난 응시 기록이 바뀌지 않아야
 * 한다"</i>고 했다. 그래서 공개를 <b>저장</b>하고, 저장된 것은 <b>다시 계산하지 않는다.</b>
 */
@SpringBootTest
@Transactional
class VocabScheduleServiceTest {

    /**
     * ★ 「오늘」을 고정하지 않고 <b>시작일을 과거로</b> 잡는다.
     *
     * <p>{@code @TestBean} 으로 시계를 갈아끼우려 했으나 이 버전에 없다. 그리고 공개 건수를
     * 절대값으로 고정하면 <b>날이 가면서 테스트가 깨진다</b> — 대신 3주 전 월요일부터 시작해
     * <b>1주차는 항상 지난 것</b>이 되게 하고, 검증은 <b>관계</b>로 한다.
     */
    private static final LocalDate TODAY = LocalDate.now();
    private static final LocalDate START = TODAY.minusWeeks(3)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

    @Autowired VocabScheduleService scheduleService;
    @Autowired VocabExamRoundRepository roundRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    short year;

    @BeforeEach
    void setUp() {
        year = (short) START.getYear();
        academy = new Academy("VS01", "일정지점", LocalTime.of(9, 0));
        em.persist(academy);
        em.flush();
        scheduleService.saveConfig(academy.getId(), year, START,
                null, null, null, null);
    }

    @Test
    @DisplayName("★ 첫 등록은 만들고 두 번째는 고친다 — 지점·연도당 한 행이다")
    void configIsSingleRowPerAcademyAndYear() {
        scheduleService.saveConfig(academy.getId(), year, START.plusDays(7),
                (short) 30, null, null, null);

        var config = scheduleService.config(academy.getId(), year);
        assertThat(config.getStartDate()).isEqualTo(START.plusDays(7));
        assertThat(config.getQuestionCount()).isEqualTo((short) 30);
        // 비워 보낸 항목은 기본값 그대로다
        assertThat(config.getChoiceCount()).isEqualTo((short) 4);
    }

    @Test
    @DisplayName("★ 같은 날을 두 번 넣어도 한 행이다 — 두 행이면 달력에 같은 날이 두 번 뜬다")
    void noExamDayIsIdempotent() {
        var first = scheduleService.addNoExamDay(academy.getId(), year,
                START.plusDays(3), "공휴일");
        var second = scheduleService.addNoExamDay(academy.getId(), year,
                START.plusDays(3), "모의고사");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(scheduleService.noExamDays(academy.getId(), year)).hasSize(1);
    }

    @Test
    @DisplayName("★★ 공개는 멱등하다 — 두 번 돌아도 같은 회차가 두 줄로 생기지 않는다")
    void publishIsIdempotent() {
        int first = scheduleService.publishDueRounds(academy.getId(), year);
        int second = scheduleService.publishDueRounds(academy.getId(), year);

        // 3주 전부터라 1주차(월~목 4건 + 금 누적 1건)는 반드시 들어 있다
        assertThat(first).isGreaterThanOrEqualTo(5);
        assertThat(second).as("두 번째는 새로 만들 것이 없다").isZero();
        assertThat(roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academy.getId(), year))
                .hasSize(first);
    }

    @Test
    @DisplayName("★ 1주차가 월1·화2·수3·목4 + 금 누적1-4 로 공개된다 (v1 5.2)")
    void firstWeekIsPublishedAsVendorExample() {
        scheduleService.publishDueRounds(academy.getId(), year);

        assertThat(roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academy.getId(), year))
                .extracting(r -> r.getUnitFrom() + "-" + r.getUnitTo()
                        + (r.isWeekly() ? "/누적" : ""))
                .startsWith("1-1", "2-2", "3-3", "4-4", "1-4/누적");
    }

    @Test
    @DisplayName("★★ 시험 없는 날을 뒤늦게 추가해도 이미 공개된 회차는 그대로다 (v1 5.6)")
    void publishedRoundsNeverChange() {
        scheduleService.publishDueRounds(academy.getId(), year);
        var before = roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academy.getId(), year)
                .stream().map(r -> r.getOpenDate() + "/" + r.getUnitFrom() + "-" + r.getUnitTo())
                .toList();

        // 이미 지난 수요일을 「시험 없는 날」로 넣는다 — 실수로 넣는 일이 실제로 있다
        scheduleService.addNoExamDay(academy.getId(), year, START.plusDays(2), "착오");
        scheduleService.publishDueRounds(academy.getId(), year);

        var after = roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academy.getId(), year)
                .stream().map(r -> r.getOpenDate() + "/" + r.getUnitFrom() + "-" + r.getUnitTo())
                .toList();

        assertThat(after)
                .as("★ 학생이 이미 응시한 회차의 범위가 바뀌면 안 된다")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("★ 미리보기는 저장하지 않는다 — 보기만 해도 회차가 공개되면 안 된다")
    void previewSavesNothing() {
        var preview = scheduleService.preview(academy.getId(), year, START.plusDays(30));

        assertThat(preview).isNotEmpty();
        assertThat(roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academy.getId(), year))
                .isEmpty();
    }

    @Test
    @DisplayName("★ 미래 회차는 공개하지 않는다 — 오늘까지만이다")
    void doesNotPublishFutureRounds() {
        scheduleService.publishDueRounds(academy.getId(), year);

        assertThat(roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academy.getId(), year))
                .allSatisfy(r -> assertThat(r.getOpenDate()).isBeforeOrEqualTo(TODAY));
        // 그리고 미래 회차가 계산에는 있다 — 공개만 안 된 것이다
        assertThat(scheduleService.preview(academy.getId(), year, TODAY.plusWeeks(2)))
                .anyMatch(r -> r.date().isAfter(TODAY));
    }

    @Test
    @DisplayName("★ 일정을 안 쓰는 지점은 0을 돌려준다 — 배치가 멈추면 안 된다")
    void unconfiguredAcademyReturnsZero() {
        var other = new Academy("VS02", "미설정지점", LocalTime.of(9, 0));
        em.persist(other);
        em.flush();

        assertThat(scheduleService.publishDueRounds(other.getId(), year)).isZero();
    }

    @Test
    @DisplayName("★ 시험 없는 날을 지우면 앞으로의 계산만 바뀐다")
    void removingNoExamDayAffectsFutureOnly() {
        scheduleService.addNoExamDay(academy.getId(), year, START.plusDays(1), "공휴일");
        var withHoliday = scheduleService.preview(academy.getId(), year, START.plusDays(4));

        scheduleService.removeNoExamDay(academy.getId(), year, START.plusDays(1));
        var without = scheduleService.preview(academy.getId(), year, START.plusDays(4));

        assertThat(withHoliday).hasSizeLessThan(without.size());
    }
}
