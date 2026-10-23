package com.dlab.domain.vocab.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.vocab.entity.VocabExamRound;
import com.dlab.domain.vocab.entity.VocabNoExamDay;
import com.dlab.domain.vocab.entity.VocabScheduleConfig;
import com.dlab.domain.vocab.repository.VocabExamRoundRepository;
import com.dlab.domain.vocab.repository.VocabNoExamDayRepository;
import com.dlab.domain.vocab.repository.VocabScheduleConfigRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D:VOCA 지점 일정 (v1 5장 · 7장).
 *
 * <h2>★★ 일정을 미리 만들어 두지 않는다 (v1 5.6)</h2>
 * <i>"시험 없는 날이 나중에 추가되면 이후 일정이 모두 밀려야 하기 때문이다."</i>
 * 그래서 {@link #preview} 는 <b>계산</b>이고, 저장은 {@link #publishDueRounds} 가
 * <b>공개 시점에만</b> 한다.
 *
 * <h2>★ 공개된 회차는 다시 계산하지 않는다</h2>
 * 이미 저장된 회차를 계산 결과로 덮으면, 시험 없는 날을 뒤늦게 추가했을 때
 * <b>학생이 이미 응시한 회차의 범위가 바뀐다.</b>
 */
@Service
@RequiredArgsConstructor
public class VocabScheduleService {

    private final VocabScheduleConfigRepository configRepository;
    private final VocabNoExamDayRepository noExamDayRepository;
    private final VocabExamRoundRepository roundRepository;
    private final AcademyRepository academyRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public VocabScheduleConfig config(Long academyId, Short year) {
        return configRepository.findByAcademyIdAndYearAndDeletedFalse(academyId, year)
                .orElseThrow(() -> new BusinessException(ErrorCode.VOCAB_SCHEDULE_NOT_CONFIGURED));
    }

    /** 지점 시작일·출제 설정. 없으면 만들고 있으면 고친다 — 지점·연도당 한 행이다. */
    @Transactional
    public VocabScheduleConfig saveConfig(Long academyId, Short year, LocalDate startDate,
                                          Short questionCount, Short weeklyQuestionCap,
                                          Short choiceCount, Short multipleChoiceRate,
                                          Boolean includePhrase, Boolean showWordDetail) {
        var existing = configRepository.findByAcademyIdAndYearAndDeletedFalse(academyId, year);
        if (existing.isPresent()) {
            var config = existing.get();
            config.modify(startDate, questionCount, weeklyQuestionCap,
                    choiceCount, multipleChoiceRate, includePhrase, showWordDetail);
            return config;
        }
        if (startDate == null) {
            // 첫 등록에는 시작일이 있어야 한다 — 없으면 계산 기준이 없다
            throw new BusinessException(ErrorCode.VOCAB_SCHEDULE_NOT_CONFIGURED);
        }
        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        var config = new VocabScheduleConfig(academy, year, startDate);
        config.modify(null, questionCount, weeklyQuestionCap, choiceCount,
                multipleChoiceRate, includePhrase, showWordDetail);
        return configRepository.save(config);
    }

    @Transactional(readOnly = true)
    public List<VocabNoExamDay> noExamDays(Long academyId, Short year) {
        return noExamDayRepository.findByAcademyIdAndYearAndDeletedFalse(academyId, year);
    }

    /**
     * 시험 없는 날 등록.
     *
     * <p>★ 같은 날을 두 번 넣어도 한 행이다 — 두 행이면 달력 미리보기에 같은 날이 두 번 뜬다.
     */
    @Transactional
    public VocabNoExamDay addNoExamDay(Long academyId, Short year,
                                       LocalDate date, String reason) {
        return noExamDayRepository
                .findByAcademyIdAndYearAndExamDateAndDeletedFalse(academyId, year, date)
                .orElseGet(() -> {
                    Academy academy = academyRepository.findById(academyId)
                            .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
                    return noExamDayRepository.save(
                            new VocabNoExamDay(academy, year, date, reason));
                });
    }

    /**
     * 시험 없는 날 삭제.
     *
     * <p>⚠️ <b>지난 회차는 되돌아가지 않는다.</b> 이미 공개된 회차는 저장돼 있어
     * 그대로고, 바뀌는 것은 <b>앞으로의 계산</b>뿐이다 — 그게 v1 5.6 이 요구한 것이다.
     */
    @Transactional
    public void removeNoExamDay(Long academyId, Short year, LocalDate date) {
        noExamDayRepository
                .findByAcademyIdAndYearAndExamDateAndDeletedFalse(academyId, year, date)
                .ifPresent(VocabNoExamDay::markDeleted);
    }

    /**
     * 회차 달력 미리보기 (v1 7장).
     *
     * <p>★ <b>저장하지 않는다.</b> 관리자가 시험 없는 날을 넣기 전에 결과를 보는 화면이고,
     * 여기서 저장하면 보기만 해도 회차가 공개된다.
     */
    @Transactional(readOnly = true)
    public List<RoundScheduler.Round> preview(Long academyId, Short year, LocalDate until) {
        var config = config(academyId, year);
        return RoundScheduler.calculate(config.getStartDate(), until, noExamDates(academyId, year));
    }

    /**
     * 오늘까지의 회차를 공개한다 (v1 5.4).
     *
     * <p><i>"회차는 예정일이 되면 공개되고, 이후 계속 공개 상태를 유지한다."</i>
     *
     * <p>★ <b>멱등하다.</b> 이미 저장된 회차는 (날짜, 누적여부)로 찾아 건너뛴다 — 배치가
     * 두 번 돌거나 인스턴스가 여럿이어도 같은 회차가 두 줄로 생기지 않는다.
     *
     * <p>⚠️ 공개 <b>시각</b>은 미확정이다(v1 Q5). 지금은 날짜 단위로 본다 — 그날이 되면
     * 공개된 것으로 다룬다.
     *
     * @return 이번에 새로 공개된 회차 수
     */
    @Transactional
    public int publishDueRounds(Long academyId, Short year) {
        var config = configRepository.findByAcademyIdAndYearAndDeletedFalse(academyId, year)
                .orElse(null);
        if (config == null) {
            return 0;   // 일정을 안 쓰는 지점이다 — 배치가 멈추면 안 된다
        }
        LocalDate today = LocalDate.now(clock);
        var calculated = RoundScheduler.calculate(
                config.getStartDate(), today, noExamDates(academyId, year));

        int created = 0;
        for (var round : calculated) {
            boolean exists = roundRepository
                    .findByAcademyIdAndYearAndOpenDateAndWeeklyAndDeletedFalse(
                            academyId, year, round.date(), round.weekly())
                    .isPresent();
            if (exists) {
                continue;
            }
            roundRepository.save(new VocabExamRound(
                    config.getAcademy(), year, round.roundNo(), (short) round.cycle(),
                    round.date(), round.weekly(),
                    (short) round.unitFrom(), (short) round.unitTo()));
            created++;
        }
        return created;
    }

    /** 공개된 회차 — 학생이 보는 목록의 원천이다. */
    @Transactional(readOnly = true)
    public List<VocabExamRound> openRounds(Long academyId, Short year) {
        return roundRepository
                .findByAcademyIdAndYearAndDeletedFalseOrderByRoundNoAsc(academyId, year);
    }

    private Set<LocalDate> noExamDates(Long academyId, Short year) {
        return noExamDayRepository.findByAcademyIdAndYearAndDeletedFalse(academyId, year)
                .stream().map(VocabNoExamDay::getExamDate)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
