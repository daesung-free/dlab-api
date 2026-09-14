package com.dlab.domain.facility.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.facility.entity.SeatAssignment;
import com.dlab.domain.facility.entity.SeatPresence;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석배치도 (독서실 좌석배치표).
 *
 * <p>클라이언트가 DSA 기능확인 회신에서 <b>직접 추가하며 "사용"으로 표기한 메뉴</b>다.
 *
 * <h2>배정 관리와 다른 화면이다</h2>
 * <ul>
 *   <li><b>배정 관리</b>(F-4.10-3) — "누구에게 어느 좌석을 줄 것인가". 명단 → 좌석</li>
 *   <li><b>좌석배치도</b> — "지금 이 구역이 어떤 상태인가". 도면 → 사람</li>
 * </ul>
 * 현장 확인은 명단이 아니라 도면으로 하기 때문에 화면을 나눈다.
 *
 * <h2>★ 상태는 두 축이고 합치면 안 된다</h2>
 * <ul>
 *   <li><b>배정 축</b> — 배정됨 / 미배정 / 사용중지({@code usable=false})</li>
 *   <li><b>재실 축</b> — {@link SeatPresence}</li>
 * </ul>
 * 합치면 "배정됐지만 미등원"과 "애초에 빈자리"가 구분되지 않아, 사감이 누구를 찾아야 하는지
 * 알 수 없다. 화면도 색(배정+재실 조합)과 점(실시간 재실)으로 나눠 표기한다.
 *
 * <h2>재실 판정을 화면이 하지 않는다</h2>
 * 출결 로그와 좌석 정보를 화면에서 조합하면 <b>새로고침마다 값이 흔들린다.</b>
 * 서버가 계산해서 내린다 — 키오스크 {@code getStudyAreaSeatState}와 <b>같은 규칙</b>이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatLayoutService {

    private final StudyAreaRepository studyAreaRepository;
    private final SeatMasterRepository seatMasterRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final com.dlab.domain.user.repository.ClassAssignmentRepository classAssignmentRepository;
    private final Clock clock;

    /** 구역 목록. 화면이 구역을 골라야 배치도를 열 수 있다. */
    public List<AreaSummary> areas(AuthPrincipal me, Long academyId) {
        return areas(me, academyId, false, null);
    }

    /**
     * 구역 목록.
     *
     * <p><b>{@code includeInactive}는 관리 화면 전용이다.</b> 배치도·배정 화면은 비활성
     * 구역을 보면 안 되지만(고를 수 있게 되면 안 쓰는 구역에 학생이 배정된다), 관리 화면에서
     * 까지 숨기면 <b>한 번 끈 구역을 다시 켤 방법이 없어진다.</b>
     */
    public List<AreaSummary> areas(AuthPrincipal me, Long academyId, boolean includeInactive,
                                   Long buildingId) {
        Long resolved = requireAcademyAccess(me, academyId);
        var areas = includeInactive
                ? studyAreaRepository.findAllByAcademyId(resolved, buildingId)
                : studyAreaRepository.findActiveByAcademyId(resolved).stream()
                        .filter(a -> buildingId == null
                                || buildingId.equals(a.getBuilding().getId()))
                        .toList();
        return areas.stream()
                .map(a -> new AreaSummary(
                        a.getId(), a.getBuilding().getId(), a.getBuilding().getName(),
                        a.getAreaCd(), a.getAreaNm(), a.getSortOrder(), a.isActive(),
                        seatMasterRepository.findByStudyAreaId(a.getId()).size()))
                .toList();
    }

    /**
     * 구역 배치도.
     *
     * <p><b>사용중지 좌석도 내린다</b> — 빼면 도면에 구멍이 생겨 좌표가 어긋나 보인다.
     *
     * <p>학생 이름은 <b>기본이 마스킹</b>이다(도면은 벽에 띄워두는 일이 있다). 다만
     * 현장에서 좌석 주인을 확인해야 하는 경우가 있어 다른 목록과 같은 규칙으로
     * {@code unmask}를 연다 — <b>상위 관리자에게만</b> 먹고, 화면은 {@code masked}로
     * 실제 적용 여부를 안다(모르면 "이름이 잘못 저장됐다"는 오인 문의가 생긴다).
     *
     * <p><b>고정반을 함께 내린다.</b> 좌석만 보고는 어느 반 학생인지 알 수 없어
     * 현장에서 쓸모가 준다.
     */
    public List<SeatCell> layout(AuthPrincipal me, Long studyAreaId, boolean unmask) {
        boolean raw = unmask && com.dlab.common.privacy.PersonalDataPolicy.canViewRaw(me);
        StudyArea area = studyAreaRepository.findById(studyAreaId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND,
                        "구역을 찾을 수 없습니다."));
        if (!me.canAccessAcademy(area.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        Map<Long, AttendanceEventType> lastEvent =
                lastEventByEnrollment(area.getAcademy().getId());

        Map<String, SeatAssignment> assignmentBySeat = new HashMap<>();
        seatAssignmentRepository.findActiveByStudyAreaId(studyAreaId)
                .forEach(sa -> assignmentBySeat.put(sa.getSeat().getSeatCd(), sa));

        // 좌석 수만큼 조회하지 않도록 배정된 학생의 반을 한 번에 받아 둔다
        Map<Long, com.dlab.domain.user.entity.ClassMaster> classByEnrollment = new HashMap<>();
        List<Long> enrollmentIds = assignmentBySeat.values().stream()
                .map(sa -> sa.getEnrollment().getId()).toList();
        if (!enrollmentIds.isEmpty()) {
            classAssignmentRepository.findActiveFixedByEnrollmentIds(enrollmentIds)
                    .forEach(ca -> classByEnrollment.put(
                            ca.getEnrollment().getId(), ca.getClassMaster()));
        }

        return seatMasterRepository.findByStudyAreaId(studyAreaId).stream()
                .map(seat -> {
                    SeatAssignment assignment = assignmentBySeat.get(seat.getSeatCd());
                    var clazz = assignment == null ? null
                            : classByEnrollment.get(assignment.getEnrollment().getId());
                    return new SeatCell(
                            seat.getId(),
                            seat.getSeatCd(),
                            seat.getSeatNm(),
                            seat.getXPos(),
                            seat.getYPos(),
                            seat.isUsable(),
                            assignment == null ? null : assignment.getEnrollment().getId(),
                            assignment == null ? null
                                    : assignment.getEnrollment().getStudentNo(),
                            assignment == null ? null
                                    : maskName(assignment.getEnrollment()
                                            .getStudent().getName(), raw),
                            clazz == null ? null : clazz.getId(),
                            clazz == null ? null : clazz.getName(),
                            presenceOf(assignment, lastEvent),
                            !raw);
                })
                .toList();
    }

    /**
     * 좌석 사용중지·해제 (고장·공사).
     *
     * <p><b>배정된 좌석은 중지할 수 없다.</b> 중지하면 배정 대상에서 빠지는데 이미 앉은
     * 학생이 그대로 남아 "사용중지인데 사람이 있는" 상태가 된다 — 배정을 먼저 푼다.
     */
    @Transactional
    public void changeUsable(AuthPrincipal me, Long seatId, boolean usable) {
        var seat = seatMasterRepository.findDetailById(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
        if (!me.canAccessAcademy(seat.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        if (!usable && seatAssignmentRepository.findActiveBySeatId(seatId).isPresent()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "배정된 좌석은 사용중지할 수 없습니다. 배정을 먼저 해제하세요.");
        }

        if (usable) {
            seat.enable();
        } else {
            seat.disable();
        }
    }

    /**
     * 재실 판정.
     *
     * <p>배정이 없으면 {@link SeatPresence#EMPTY}다 — 태깅을 볼 필요조차 없다.
     * <b>사용중지 좌석도 EMPTY다</b>: 사용중지는 배정 축이라 재실 축과 별개로 표기된다.
     */
    private static String maskName(String name, boolean raw) {
        return raw ? name : com.dlab.common.privacy.Masking.name(name);
    }

    private SeatPresence presenceOf(SeatAssignment assignment,
                                    Map<Long, AttendanceEventType> lastEvent) {
        if (assignment == null) {
            return SeatPresence.EMPTY;
        }
        return SeatPresence.of(lastEvent.get(assignment.getEnrollment().getId()));
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

    private Long requireAcademyAccess(AuthPrincipal me, Long academyId) {
        return me.requireAcademyScope(academyId);
    }

    /**
     * @param buildingName 어느 관인가. <b>화면이 이걸 안 띄우면 같은 이름의 구역이 둘씩
     *                     보인다</b> — 본관 A 와 별관 A 를 구분할 수가 없다
     * @param seatCount    구역 수용인원. 좌석 수에서 센다 — 별도 컬럼이면 어긋난다
     */
    public record AreaSummary(Long id, Long buildingId, String buildingName,
                              String areaCd, String areaNm, short sortOrder,
                              boolean active, int seatCount) {
    }

    /**
     * @param usable      사용중지 여부(배정 축). {@code presence}와 별개다
     * @param studentName {@code masked}가 참이면 가려진 값이다
     * @param className   고정반. 반 미배정이면 비어 있다
     * @param masked      이름이 실제로 가려졌는지. 화면이 이 값을 보고 또 가리지 않는다
     */
    public record SeatCell(
            Long seatId,
            String seatCd,
            String seatNm,
            int xPos,
            int yPos,
            boolean usable,
            Long enrollmentId,
            String studentNo,
            String studentName,
            Long classId,
            String className,
            SeatPresence presence,
            boolean masked) {

        /** 배정 축. 화면이 색을 고를 때 재실 축과 조합한다. */
        public String assignmentState() {
            if (!usable) {
                return "DISABLED";
            }
            return enrollmentId == null ? "UNASSIGNED" : "ASSIGNED";
        }
    }
}
