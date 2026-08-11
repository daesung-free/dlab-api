package com.dlab.domain.report.service;

import com.dlab.domain.report.entity.RankingPeriod;
import com.dlab.domain.report.entity.StudyTimeRanking;
import com.dlab.domain.report.repository.DailyStudyRepository;
import com.dlab.domain.report.repository.StudyTimeRankingRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 순공시간 랭킹 적재·조회 (F-4.11-6).
 *
 * <p>적재는 하루 한 번, 출결 확정 배치 직후다. 조회는 앱 홈이 매번 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudyTimeRankingService {

    private final DailyStudyRepository dailyStudyRepository;
    private final StudyTimeRankingRepository rankingRepository;
    private final EntityManager em;

    /** 한 학생의 집계 한 줄. */
    private record Row(Long enrollmentId, Long academyId, short year, int studyMinutes) {
    }

    /**
     * 기준일이 속한 기간의 랭킹을 다시 만든다.
     *
     * <p><b>지우고 다시 넣는다.</b> 출결이 뒤늦게 정정되면(사유 승인·관리자 수정)
     * 순공시간이 바뀌므로 같은 기간을 여러 번 돌리게 된다 — 누적하면 등수가 중복된다.
     *
     * <p><b>전체·지점 랭킹을 한 번 읽어 둘 다 만든다.</b> 지점 수만큼 쿼리를 나누면
     * 같은 행을 아홉 번 읽는다.
     */
    @Transactional
    public int rebuild(RankingPeriod period, LocalDate date) {
        LocalDate start = period.startOf(date);
        LocalDate end = period.endOf(date);

        List<Row> rows = dailyStudyRepository.sumByEnrollment(start, end).stream()
                .map(r -> new Row(((Number) r[0]).longValue(),
                        ((Number) r[1]).longValue(),
                        ((Number) r[2]).shortValue(),
                        ((Number) r[3]).intValue()))
                .toList();

        rankingRepository.deletePeriod(period, start);
        // 삭제가 INSERT보다 먼저 나가야 유니크 제약에 걸리지 않는다
        em.flush();

        int saved = rank(rows, null, period, start);
        for (Long academyId : rows.stream().map(Row::academyId).distinct().toList()) {
            saved += rank(rows.stream().filter(r -> r.academyId().equals(academyId)).toList(),
                    academyId, period, start);
        }
        return saved;
    }

    /**
     * 등수를 매겨 저장한다.
     *
     * <p><b>동점이면 같은 등수다</b>(1, 1, 3). 순공시간이 분 단위라 동점이 실제로 나오는데,
     * 임의로 순서를 매기면 같은 시간을 공부한 두 학생에게 다른 등수가 보인다.
     */
    private int rank(List<Row> rows, Long academyId, RankingPeriod period, LocalDate start) {
        List<Row> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparingInt(Row::studyMinutes).reversed());

        Map<Long, Academy> academies = new HashMap<>();
        int ranking = 0;
        int previousMinutes = -1;

        for (int i = 0; i < sorted.size(); i++) {
            Row row = sorted.get(i);
            if (row.studyMinutes() != previousMinutes) {
                ranking = i + 1;                       // 동점 다음은 건너뛴 등수(1,1,3)
                previousMinutes = row.studyMinutes();
            }
            Academy academy = academyId == null ? null
                    : academies.computeIfAbsent(academyId,
                            id -> em.getReference(Academy.class, id));

            rankingRepository.save(new StudyTimeRanking(academy,
                    em.getReference(StudentEnrollment.class, row.enrollmentId()),
                    row.year(), period, start, row.studyMinutes(), ranking));
        }
        return sorted.size();
    }

    // ── 조회 ──────────────────────────────────────────────────

    /**
     * 앱 홈 랭킹 (A-3) — 전체 1등 · 지점 1등 · 내 등수.
     *
     * <p><b>배치가 아직 안 돈 기간이면 비어 있다</b> — 그 자리에서 집계하지 않는다.
     * 전교생이 여는 화면이라 같은 계산을 수천 번 다시 하게 되고, 무엇보다
     * 오늘 순공은 새벽 확정 전까지 확정값이 아니다.
     */
    @Transactional(readOnly = true)
    public RankingView view(StudentEnrollment enrollment, RankingPeriod period, LocalDate date) {
        LocalDate start = period.startOf(date);
        Long academyId = enrollment.getAcademy().getId();

        return new RankingView(
                period, start,
                top(null, period, start, 1).stream().findFirst().orElse(null),
                top(academyId, period, start, 1).stream().findFirst().orElse(null),
                mine(enrollment.getId(), null, period, start),
                mine(enrollment.getId(), academyId, period, start),
                rankingRepository.countParticipants(academyId, period, start));
    }

    @Transactional(readOnly = true)
    public List<RankingEntry> top(Long academyId, RankingPeriod period,
                                  LocalDate periodStart, int size) {
        return rankingRepository
                .findTop(academyId, period, periodStart, PageRequest.of(0, size))
                .stream().map(RankingEntry::from).toList();
    }

    private RankingEntry mine(Long enrollmentId, Long academyId,
                              RankingPeriod period, LocalDate start) {
        return rankingRepository.findMine(enrollmentId, academyId, period, start)
                .map(RankingEntry::from).orElse(null);
    }

    /**
     * @param overallTop  전 지점 1등. 적재 전이면 {@code null}
     * @param myOverall   내 전체 등수. 순공 기록이 없으면 {@code null} — 0등으로 내리면
     *                    꼴찌처럼 보인다
     * @param academySize 지점 참여 인원. "N명 중 3등"의 분모다
     */
    public record RankingView(RankingPeriod period, LocalDate periodStart,
                              RankingEntry overallTop, RankingEntry academyTop,
                              RankingEntry myOverall, RankingEntry myAcademy,
                              long academySize) {
    }

    public record RankingEntry(int ranking, String studentName, String academyName,
                               int studyMinutes) {

        static RankingEntry from(StudyTimeRanking r) {
            return new RankingEntry(r.getRanking(),
                    r.getEnrollment().getStudent().getName(),
                    r.getEnrollment().getAcademy().getName(),
                    r.getStudyMinutes());
        }
    }
}
