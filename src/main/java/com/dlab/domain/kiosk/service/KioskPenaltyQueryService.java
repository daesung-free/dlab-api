package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.dto.DsaRows;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 상벌점 조회 (DSA 3.28 {@code getPointStdList}).
 *
 * <p><b>학생 단건이 아니라 지점 전체를 내린다.</b> 키오스크가 한 번 받아 {@code std_no}로
 * 쪼개 10분 캐싱하는 구조라(그쪽 {@code StudentDetailDsaService.findPoints}),
 * 학생별로 응답하면 캐시가 전부 빗나가 매 화면마다 호출이 터진다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskPenaltyQueryService {

    private final PenaltyPointRepository penaltyPointRepository;
    private final Clock clock;

    public List<DsaRows.PointRow> points(Long academyId, String startDate, String endDate) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = parseDate(startDate, today.withDayOfMonth(1));
        LocalDate to = parseDate(endDate, today);

        ZoneId zone = clock.getZone();
        Instant fromInstant = from.atStartOfDay(zone).toInstant();
        // 끝 날짜를 포함해야 한다 — 오늘 부여된 벌점이 오늘 조회에서 빠지면
        // 학생이 화면에서 확인하지 못한다
        Instant toInstant = to.plusDays(1).atStartOfDay(zone).toInstant();

        return penaltyPointRepository.findByAcademyAndPeriod(academyId, fromInstant, toInstant)
                .stream()
                .map(p -> new DsaRows.PointRow(
                        p.getEnrollment().getStudentNo(),
                        LocalDate.ofInstant(p.getOccurredAt(), zone).toString(),
                        reasonOf(p),
                        signedPoints(p)))
                .toList();
    }

    /** 사유가 비어 있으면 항목명으로 대체한다 — 자동 부여분은 사유를 안 남긴다. */
    private String reasonOf(PenaltyPoint p) {
        if (p.getReason() != null && !p.getReason().isBlank()) {
            return p.getReason();
        }
        return p.getPenaltyItem() == null ? null : p.getPenaltyItem().getItemName();
    }

    /**
     * 벌점은 음수로 내린다.
     *
     * <p>우리는 {@code points}를 항상 양수로 저장하고 상/벌 구분을 {@code category}로 둔다.
     * 키오스크는 카테고리를 안 받으므로 부호로 구분하지 않으면
     * <b>벌점 10점이 상점 10점으로 보인다.</b>
     */
    private int signedPoints(PenaltyPoint p) {
        int value = Math.abs(p.getPoints());
        boolean demerit = p.getPenaltyItem() != null
                && p.getPenaltyItem().getCategory() == PenaltyCategory.DEMERIT;
        return demerit ? -value : value;
    }

    private LocalDate parseDate(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }
}
