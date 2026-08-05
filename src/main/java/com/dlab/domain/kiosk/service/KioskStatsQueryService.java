package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.common.privacy.Masking;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.attendance.service.StudyTimeCalculator;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 통계·순공시간 (DSA 3.3 · 3.5 · 3.6 · 3.11 · 3.15 · 3.21 · 3.26 · 3.27).
 *
 * <p>전부 출결 원장에서 파생한다 — 별도 집계 테이블을 두지 않았다. 지점당 학생이
 * 수백 명 규모라 그날치 원장을 훑는 비용이 작고, <b>집계 테이블을 두면 원장과 어긋났을 때
 * 어느 쪽이 맞는지 알 수 없어진다.</b> 규모가 커지면 그때 사전집계로 옮긴다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskStatsQueryService {

    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final PeriodMasterRepository periodMasterRepository;
    private final StudyTimeCalculator studyTimeCalculator;
    private final Clock clock;

    // ── 대시보드 ────────────────────────────────────────────────

    /**
     * 3.3 {@code getTotalAttendCount} — <b>지금 원내에 있는 인원</b>.
     *
     * <p>누적 등원 수가 아니다. 등원했다가 하원·조퇴한 학생과 외출 중인 학생은 빠진다 —
     * 이 숫자는 키오스크 화면에 "현재 000명"으로 뜨므로 실제 재실 인원이어야 한다.
     */
    public long currentlyPresent(Long academyId) {
        LocalDate today = LocalDate.now(clock);
        return lastEventByEnrollment(academyId, today).values().stream()
                .filter(this::isPresent)
                .count();
    }

    private boolean isPresent(AttendanceEventType last) {
        return last == AttendanceEventType.CHECK_IN
                || last == AttendanceEventType.LATE
                || last == AttendanceEventType.RETURN;
    }

    /**
     * 3.21 {@code getAttendState} — 기간 내 조퇴·결석·지각 수(지점 전체).
     *
     * <p><b>결석은 여기서 셀 수 없다.</b> "안 찍은 것"이라 원장에 남지 않는다 —
     * 일자 집계 배치가 {@code attendance_daily_status}를 확정해야 나오는 값이다.
     * 배치가 없는 지금은 <b>0으로 나간다</b>. 0이 "결석이 없다"로 읽히므로
     * 배치를 붙이기 전에는 이 필드를 믿으면 안 된다.
     */
    public AttendCounts attendCounts(Long academyId, String startDate, String endDate) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = parseDate(startDate, today);
        LocalDate to = parseDate(endDate, today);

        Map<AttendanceEventType, Long> counts = countEvents(
                taggingLogRepository.findByAcademyAndPeriod(academyId, from, to));

        return new AttendCounts(
                counts.getOrDefault(AttendanceEventType.EARLY_LEAVE, 0L),
                0L,   // 결석 — 배치 대기
                counts.getOrDefault(AttendanceEventType.LATE, 0L));
    }

    // ── 학생 상세 ───────────────────────────────────────────────

    /** 3.26 {@code getStdAttendState} — 학생별 결석·조퇴·외출 횟수. */
    public AttendCounts studentAttendState(Long academyId, String rfidNo,
                                           String startDate, String endDate) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);
        LocalDate today = LocalDate.now(clock);
        LocalDate from = parseDate(startDate, today.withDayOfMonth(1));
        LocalDate to = parseDate(endDate, today);

        Map<AttendanceEventType, Long> counts = countEvents(
                taggingLogRepository.findByEnrollmentAndPeriod(enrollment.getId(), from, to));

        long outing = counts.getOrDefault(AttendanceEventType.OUTING, 0L)
                + counts.getOrDefault(AttendanceEventType.EXCUSED_OUTING, 0L);

        return new AttendCounts(
                counts.getOrDefault(AttendanceEventType.EARLY_LEAVE, 0L),
                0L,   // 결석 — 배치 대기
                outing);
    }

    /** 3.15 {@code getAttendListStd} — 학생별 당월 지각 수. */
    public long lateCount(Long academyId, String rfidNo, String month) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);
        YearMonth target = parseMonth(month);

        return taggingLogRepository.findByEnrollmentAndPeriod(
                        enrollment.getId(), target.atDay(1), target.atEndOfMonth()).stream()
                .filter(l -> l.getEventType() == AttendanceEventType.LATE)
                .count();
    }

    // ── 순공시간 ────────────────────────────────────────────────

    /** 3.27 {@code getStudyTimeList} — 기간별·일별 순공시간(지점 전체). */
    public List<StudyTimeRow> studyTimes(Long academyId, String startDate, String endDate) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = parseDate(startDate, today.withDayOfMonth(1));
        LocalDate to = parseDate(endDate, today);

        List<StudyTimeRow> rows = new ArrayList<>();
        dailyStudyTime(academyId, from, to).forEach((key, duration) ->
                rows.add(new StudyTimeRow(
                        key.date().toString(), key.studentName(), key.studentNo(),
                        StudyTimeCalculator.format(duration))));

        rows.sort(Comparator.comparing(StudyTimeRow::studyDt).thenComparing(StudyTimeRow::stdNo));
        return rows;
    }

    /**
     * 3.11 {@code getLastWeekStudyTimeList} — 전주(월~금) 순공 순위.
     *
     * <p>주말을 뺀다(규격서 "전주(월~금)"). 이 학원은 토요일에도 운영하지만
     * 순위 산정 기준은 규격서를 따른다.
     */
    public List<RankRow> lastWeekRanking(Long academyId) {
        Map<String, Duration> byStudent = lastWeekTotals(academyId);

        List<Map.Entry<String, Duration>> sorted = byStudent.entrySet().stream()
                .sorted(Map.Entry.<String, Duration>comparingByValue().reversed())
                .toList();

        List<RankRow> rows = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            rows.add(new RankRow(
                    String.valueOf(i + 1),
                    Masking.name(sorted.get(i).getKey()),
                    StudyTimeCalculator.format(sorted.get(i).getValue())));
        }
        return rows;
    }

    /**
     * 3.5 {@code getFirstLastWeekStudyTimeStd} — 전주 1위.
     *
     * <p>규격서: <b>동점자가 여럿일 수 있고</b>, 없으면 빈 목록이다.
     * 여기 이름은 마스킹하지 않는다 — 규격서 샘플이 {@code "홍길동"}이다
     * (1위를 화면에 축하 표시하는 용도라 실명이 맞다).
     */
    public List<TopRow> lastWeekTop(Long academyId) {
        Map<String, Duration> byStudent = lastWeekTotals(academyId);
        if (byStudent.isEmpty()) {
            return List.of();
        }

        Duration best = byStudent.values().stream().max(Duration::compareTo).orElse(Duration.ZERO);
        if (best.isZero()) {
            return List.of();
        }

        return byStudent.entrySet().stream()
                .filter(e -> e.getValue().equals(best))
                .map(e -> new TopRow(e.getKey(), StudyTimeCalculator.format(e.getValue())))
                .toList();
    }

    /** 3.6 {@code getAvgLastWeekStudyTime} — 전주 평균. 기록이 있는 학생만 분모에 넣는다. */
    public String lastWeekAverage(Long academyId) {
        Map<String, Duration> byStudent = lastWeekTotals(academyId);
        if (byStudent.isEmpty()) {
            return StudyTimeCalculator.format(Duration.ZERO);
        }
        long totalMinutes = byStudent.values().stream().mapToLong(Duration::toMinutes).sum();
        return StudyTimeCalculator.format(
                Duration.ofMinutes(totalMinutes / byStudent.size()));
    }

    /** 전주 월~금 학생별 합계(이름 기준). */
    private Map<String, Duration> lastWeekTotals(Long academyId) {
        LocalDate monday = LocalDate.now(clock)
                .minusWeeks(1)
                .with(DayOfWeek.MONDAY);

        Map<String, Duration> byStudent = new HashMap<>();
        dailyStudyTime(academyId, monday, monday.plusDays(4))
                .forEach((key, duration) -> byStudent.merge(
                        key.studentName(), duration, Duration::plus));
        return byStudent;
    }

    /** (날짜·학생)별 순공시간. 교시 마스터는 요일 구분별로 한 번만 읽는다. */
    private Map<StudentDay, Duration> dailyStudyTime(Long academyId, LocalDate from, LocalDate to) {
        List<AttendanceTaggingLog> logs =
                taggingLogRepository.findByAcademyAndPeriod(academyId, from, to);
        if (logs.isEmpty()) {
            return Map.of();
        }

        Map<DayType, List<PeriodMaster>> periodCache = new EnumMap<>(DayType.class);
        LocalTime now = LocalTime.now(clock);

        Map<StudentDay, List<AttendanceTaggingLog>> grouped = logs.stream()
                .collect(Collectors.groupingBy(l -> new StudentDay(
                        l.getAttendanceDate(),
                        l.getEnrollment().getStudentNo(),
                        l.getEnrollment().getStudent().getName(),
                        l.getEnrollment().getYear())));

        Map<StudentDay, Duration> result = new HashMap<>();
        grouped.forEach((key, dayLogs) -> {
            List<PeriodMaster> periods = periodCache.computeIfAbsent(
                    DayType.of(key.date()),
                    dt -> periodMasterRepository.findByDayType(academyId, key.year(), dt));
            result.put(key, studyTimeCalculator.calculate(dayLogs, periods, now));
        });
        return result;
    }

    // ── 공통 ────────────────────────────────────────────────────

    private Map<AttendanceEventType, Long> countEvents(List<AttendanceTaggingLog> logs) {
        return logs.stream().collect(Collectors.groupingBy(
                AttendanceTaggingLog::getEventType, Collectors.counting()));
    }

    private Map<Long, AttendanceEventType> lastEventByEnrollment(Long academyId, LocalDate date) {
        Map<Long, AttendanceEventType> result = new HashMap<>();
        taggingLogRepository.findByAcademyAndPeriod(academyId, date, date).stream()
                .sorted(Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .forEach(l -> result.put(l.getEnrollment().getId(), l.getEventType()));
        return result;
    }

    private StudentEnrollment requireEnrollment(Long academyId, String rfidNo) {
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .filter(e -> e.getAcademy().getId().equals(academyId))
                .orElseThrow(() -> new DsaApiException(DsaCode.INVALID_KEY, "등록되지 않은 카드입니다."));
    }

    private LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new DsaApiException(DsaCode.INVALID_PARAMETER);
        }
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now(clock));
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new DsaApiException(DsaCode.INVALID_MONTH);
        }
    }

    /** {@code early_cnt} · {@code absence_cnt} · {@code late_cnt}(또는 {@code out_cnt}). */
    public record AttendCounts(long earlyLeave, long absence, long lateOrOuting) {
    }

    public record StudyTimeRow(String studyDt, String stdNm, String stdNo, String studyTm) {
    }

    public record RankRow(String rank, String stdNm, String studyTm) {
    }

    public record TopRow(String stdNm, String studyTm) {
    }

    private record StudentDay(LocalDate date, String studentNo, String studentName, short year) {
    }
}
