package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.dto.DsaRows;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.seat.entity.SeatAssignment;
import com.dlab.domain.seat.entity.StudyArea;
import com.dlab.domain.seat.repository.SeatAssignmentRepository;
import com.dlab.domain.seat.repository.SeatMasterRepository;
import com.dlab.domain.seat.repository.StudyAreaRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 좌석 조회 (DSA 3.7 · 3.8 · 3.10).
 *
 * <p>키오스크는 3.8(레이아웃)과 3.10(상태)을 <b>따로 호출해 {@code seat_cd}로 머지</b>한다
 * (그쪽 {@code DsaAreaService.findSeatStatusByArea}). 그래서 두 응답의 {@code seat_cd}가
 * 정확히 같은 문자열이어야 하고, 3.10에 없는 좌석은 {@code B}(빈좌석)로 간주된다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskSeatQueryService {

    /** 착석. */
    private static final String STATE_OCCUPIED = "S";
    /** 외출. */
    private static final String STATE_OUT = "D";
    /** 빈좌석(기본값). */
    private static final String STATE_EMPTY = "B";

    private final StudyAreaRepository studyAreaRepository;
    private final SeatMasterRepository seatMasterRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final Clock clock;

    /** 3.7 {@code getStudyAreaInfo} — 구역 목록. */
    public List<DsaRows.AreaRow> areas(Long academyId) {
        return studyAreaRepository.findActiveByAcademyId(academyId).stream()
                .map(a -> new DsaRows.AreaRow(a.getAreaCd(), a.getAreaNm()))
                .toList();
    }

    /**
     * 3.8 {@code getStudyAreaSeatInfo} — 구역별 좌석 레이아웃.
     *
     * <p>사용 불가 좌석도 포함한다 — 빼면 배치도에 구멍이 생겨 학생이 자기 자리를 못 찾는다.
     */
    public List<DsaRows.SeatRow> seats(Long academyId, String areaCd) {
        return area(academyId, areaCd)
                .map(a -> seatMasterRepository.findByStudyAreaId(a.getId()).stream()
                        .map(s -> new DsaRows.SeatRow(
                                s.getSeatCd(), s.getSeatNm(),
                                s.getXPos(), s.getYPos(), s.seatGn()))
                        .toList())
                .orElse(List.of());
    }

    /**
     * 3.10 {@code getStudyAreaSeatState} — 좌석 실시간 상태.
     *
     * <p><b>상태는 저장된 값이 아니라 출결에서 파생한다.</b> 자리이탈 원장(seat_move_logs)이
     * 아직 없어서(0803 재정의, §4 I-16) 지금은 그날 마지막 태깅으로 판정한다:
     * <ul>
     *   <li>배정 없음 → {@code B}</li>
     *   <li>등원·지각·복귀 후 → {@code S}</li>
     *   <li>외출·사유외출 후 → {@code D}</li>
     *   <li>하원·조퇴 후 또는 그날 태깅 없음 → {@code B}</li>
     * </ul>
     * 자리이탈 원장이 생기면 <b>이 메서드만</b> 바꾸면 된다.
     */
    public List<DsaRows.SeatStateRow> seatStates(Long academyId, String areaCd) {
        Optional<StudyArea> area = area(academyId, areaCd);
        if (area.isEmpty()) {
            return List.of();
        }

        Map<Long, AttendanceEventType> lastEvent = lastEventByEnrollment(academyId);

        return seatAssignmentRepository.findActiveByStudyAreaId(area.get().getId()).stream()
                .map(sa -> new DsaRows.SeatStateRow(
                        sa.getSeat().getSeatCd(),
                        stateOf(lastEvent.get(sa.getEnrollment().getId()))))
                .toList();
    }

    /**
     * 그날 마지막 태깅 이벤트.
     *
     * <p><b>{@code recordedAt}으로 직접 정렬한다.</b> 리포지토리 반환 순서에 기대면
     * 등원 뒤에 찍힌 외출이 앞에 와서 "외출 중인데 착석"으로 뒤집힐 수 있다.
     */
    private Map<Long, AttendanceEventType> lastEventByEnrollment(Long academyId) {
        Map<Long, AttendanceEventType> result = new HashMap<>();
        taggingLogRepository.findByAcademyIdAndAttendanceDate(academyId, LocalDate.now(clock))
                .stream()
                .sorted(Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .forEach(log -> result.put(log.getEnrollment().getId(), log.getEventType()));
        return result;
    }

    private String stateOf(AttendanceEventType last) {
        if (last == null) {
            return STATE_EMPTY;
        }
        return switch (last) {
            case CHECK_IN, LATE, RETURN -> STATE_OCCUPIED;
            case OUTING, EXCUSED_OUTING -> STATE_OUT;
            case CHECK_OUT, EARLY_LEAVE -> STATE_EMPTY;
        };
    }

    private Optional<StudyArea> area(Long academyId, String areaCd) {
        if (areaCd == null || areaCd.isBlank()) {
            return Optional.empty();
        }
        return studyAreaRepository.findByAcademyIdAndAreaCd(academyId, areaCd);
    }
}
