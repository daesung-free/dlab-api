package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.dto.DsaRows;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.SeatPresence;
import com.dlab.domain.facility.entity.AreaType;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.facility.repository.SeatMasterRepository;
import com.dlab.domain.facility.repository.StudyAreaRepository;
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

    /**
     * 공석 — 아무도 배정되지 않았다.
     *
     * <p>나머지 3값({@code S}/{@code D}/{@code N})은 {@link SeatPresence}가 갖는다.
     * <b>판정 규칙을 여기 두지 않는 이유</b>: 관리자 좌석배치도(F-4.10-3)가 같은 판정을 쓰는데
     * 두 벌로 두면 같은 좌석이 키오스크와 관리자 화면에서 다르게 보인다.
     */
    private static final String STATE_EMPTY = SeatPresence.EMPTY.dsaCode();

    private final StudyAreaRepository studyAreaRepository;
    private final SeatMasterRepository seatMasterRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final Clock clock;

    /**
     * 3.7 {@code getStudyAreaInfo} — 구역 목록.
     *
     * <p>{@code area_inwon}은 구역 총 수용인원이다. <b>좌석 수에서 센다</b> —
     * 별도 컬럼으로 두면 좌석을 추가할 때마다 같이 고쳐야 하고, 어긋나면
     * 키오스크 화면의 정원과 실제 좌석 수가 다르게 보인다.
     */
    public List<DsaRows.AreaRow> areas(Long academyId) {
        // ★ STUDY 만 내린다. 반 교실도 같은 테이블에 있어서 종류를 안 걸면
        //   단말 좌석 선택 화면에 반이 뜬다.
        return studyAreaRepository.findActiveByAcademyIdAndType(academyId, AreaType.STUDY)
                .stream()
                .map(a -> new DsaRows.AreaRow(
                        a.getAreaCd(),
                        a.getAreaNm(),
                        String.valueOf(seatMasterRepository.findByStudyAreaId(a.getId()).size())))
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
                                s.getSeatCd(),
                                String.valueOf(s.getXPos()),
                                String.valueOf(s.getYPos()),
                                s.getSeatNm(),
                                seatGn(s)))
                        .toList())
                .orElse(List.of());
    }

    /**
     * DSA {@code seat_gn} — {@code Y} 사용 / {@code N} 미사용 / {@code E} 통로.
     *
     * <p>엔티티는 {@code usable} boolean만 갖고 있어 <b>통로({@code E})를 표현하지 못한다.</b>
     * 통로 좌석을 만들지 않는 한 무해하지만, 좌석배치도에 통로를 넣으려면
     * {@code seat_master}에 유형 컬럼이 필요하다.
     */
    private String seatGn(SeatMaster seat) {
        return seat.isUsable() ? "Y" : "N";
    }

    /**
     * 3.10 {@code getStudyAreaSeatState} — 좌석 실시간 상태.
     *
     * <p><b>{@code N}(미출석)과 {@code B}(공석)는 다르다.</b> 배정된 학생이 아직 안 온 자리가
     * {@code N}, 아무도 배정되지 않은 자리가 {@code B}다. 합치면 좌석표에서
     * "결석자 자리"와 "빈 자리"가 구분되지 않아, 사감이 누구를 찾아야 하는지 알 수 없다.
     *
     * <p><b>상태는 저장된 값이 아니라 출결에서 파생한다.</b> 자리이탈 원장(seat_move_logs)이
     * 아직 없어서(0803 재정의, §4 I-16) 지금은 그날 마지막 태깅으로 판정한다:
     * <ul>
     *   <li>배정 없음 → {@code B} 공석</li>
     *   <li>배정됐지만 그날 태깅 없음 · 하원·조퇴 후 → {@code N} 미출석</li>
     *   <li>등원·지각·복귀 후 → {@code S}</li>
     *   <li>외출·사유외출 후 → {@code D}</li>
     * </ul>
     * 자리이탈 원장이 생기면 <b>이 메서드만</b> 바꾸면 된다.
     *
     * <p><b>배정이 없는 좌석도 {@code B}로 함께 내린다.</b> 배정된 자리만 응답하면
     * 키오스크가 나머지를 기본값 {@code B}로 채우긴 하지만, 그건 우연히 맞는 것이라
     * 기본값이 바뀌면 조용히 틀어진다.
     */
    public List<DsaRows.SeatStateRow> seatStates(Long academyId, String areaCd) {
        Optional<StudyArea> area = area(academyId, areaCd);
        if (area.isEmpty()) {
            return List.of();
        }

        Map<Long, AttendanceEventType> lastEvent = lastEventByEnrollment(academyId);

        Map<String, String> stateBySeat = new HashMap<>();
        seatAssignmentRepository.findActiveByStudyAreaId(area.get().getId())
                .forEach(sa -> stateBySeat.put(
                        sa.getSeat().getSeatCd(),
                        stateOf(lastEvent.get(sa.getEnrollment().getId()))));

        return seatMasterRepository.findByStudyAreaId(area.get().getId()).stream()
                .map(s -> new DsaRows.SeatStateRow(
                        s.getSeatCd(),
                        stateBySeat.getOrDefault(s.getSeatCd(), STATE_EMPTY)))
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

    /**
     * 배정된 좌석의 상태. 배정 자체가 없으면 이 메서드를 타지 않는다(그건 {@code B}).
     *
     * <p>판정은 {@link SeatPresence}가 한다 — 관리자 좌석배치도와 <b>같은 규칙</b>이어야 한다.
     */
    private String stateOf(AttendanceEventType last) {
        return SeatPresence.of(last).dsaCode();
    }

    private Optional<StudyArea> area(Long academyId, String areaCd) {
        if (areaCd == null || areaCd.isBlank()) {
            return Optional.empty();
        }
        // 교실 코드로 좌석을 조회하면 반 좌석이 단말에 그대로 나간다 — 종류까지 건다.
        return studyAreaRepository.findByAcademyIdAndAreaCdAndType(
                academyId, areaCd, AreaType.STUDY);
    }
}
