package com.dlab.domain.kiosk.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.kiosk.entity.SeatLeaveEventType;
import com.dlab.domain.kiosk.entity.SeatLeaveLog;
import com.dlab.domain.kiosk.repository.SeatLeaveLogRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.service.HomeroomScopeService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 웹 좌석 이탈 현황 (F-4.3-2).
 *
 * <p>키오스크가 보낸 이탈·복귀 로그를 <b>이탈 한 건 = 한 행</b>으로 짝지어 보여준다.
 * 로그를 그대로 나열하면 "이 학생이 몇 분 나가 있었나"를 화면이 직접 맞춰야 한다.
 *
 * <h2>짝짓기 규칙</h2>
 * <ul>
 *   <li>이탈(LEAVE) 다음에 오는 첫 {@code RETURN}/{@code AUTO_CLOSE}가 그 이탈을 닫는다</li>
 *   <li>★ {@code AUTO_CLOSE}는 <b>복귀가 아니다</b> — 키오스크 00:30 일괄 마감이다.
 *       상태를 {@link Status#AUTO_CLOSED}로 따로 두고 이탈 시간을 계산하지 않는다.
 *       복귀로 세면 23시 이탈이 "90분 이탈 후 복귀"로 보여 장시간 미복귀가 가려진다</li>
 *   <li>닫히기 전에 다음 이탈이 오면 앞 건은 {@link Status#NO_RETURN_RECORD} — 복귀 기록이
 *       유실됐거나 키오스크가 못 보낸 것이다. 추측해서 채우지 않는다</li>
 * </ul>
 *
 * <p>조회 시작일 <b>전날부터</b> 로그를 읽는다. 자정 직전 이탈의 복귀가 다음 날에 찍히므로,
 * 시작일 로그만 읽으면 그날 첫 복귀가 짝 없이 남는다.
 *
 * <p>⚠️ <b>이탈 위치(어디로 갔는지)는 없다</b> — 키오스크가 보내지 않고 I-16이 미확정이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatLeaveBoardService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 화면이 페이징 없이 받는 응답이라 범위를 묶는다. 출결 기간 조회와 같은 값이다. */
    public static final int MAX_RANGE_DAYS = 31;

    private final SeatLeaveLogRepository logRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final HomeroomScopeService homeroomScopeService;
    private final Clock clock;

    public enum Status {
        /** 지금 나가 있다. */
        OPEN,
        /** 학생이 돌아왔다. */
        RETURNED,
        /** 키오스크 00:30 일괄 마감으로 닫혔다 — <b>복귀하지 않았다.</b> */
        AUTO_CLOSED,
        /** 복귀 기록 없이 다음 이탈이 왔다. 기록 유실이다. */
        NO_RETURN_RECORD
    }

    /**
     * @param enrollmentId  {@code null}이면 키오스크가 보낸 카드·학번으로 학생을 못 찾은 건이다
     * @param studentNo     학생을 못 찾았으면 키오스크가 보낸 학번 원본
     * @param closedAt      복귀 또는 자동 마감 시각. 열려 있으면 {@code null}
     * @param minutes       이탈 시간(분). {@code OPEN}이면 지금까지 경과, {@code RETURNED}면 실제 이탈 시간,
     *                      그 외에는 알 수 없어 {@code null}
     */
    public record LeaveRow(Long leaveLogId, Long enrollmentId, String studentNo, String name,
                           String className, String areaCd, String seatCd,
                           Instant leftAt, Instant closedAt, Status status, Long minutes,
                           boolean resolved) {
    }

    /** 지금 나가 있는 학생. 이탈 중 목록 화면용이다. */
    public List<LeaveRow> current(AuthPrincipal me, Long academyId, Long classId) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        return history(me, academyId, today.minusDays(1), today, classId).stream()
                .filter(r -> r.status() == Status.OPEN)
                .sorted(Comparator.comparing(LeaveRow::leftAt))
                .toList();
    }

    /**
     * 지금 이탈 중인 학생 → 이탈 시작 시각. <b>좌석 배치도가 쓴다.</b>
     *
     * <p>권한 검사를 하지 않는다 — 부르는 쪽(배치도)이 이미 지점 접근을 확인했고,
     * 학생 정보가 아니라 좌석 상태만 파생하는 용도다.
     *
     * <p>어제치부터 읽는다. 자정을 넘긴 이탈은 키오스크가 00:30에 마감하므로
     * 그 전에 조회하면 아직 열려 있다.
     */
    public Map<Long, Instant> openLeavesOf(Long academyId) {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        List<SeatLeaveLog> logs = logRepository.findByAcademyBetween(academyId,
                today.minusDays(1).atStartOfDay(KST).toInstant(),
                today.plusDays(1).atStartOfDay(KST).toInstant());

        Map<Long, Instant> open = new LinkedHashMap<>();
        for (SeatLeaveLog log : logs) {
            if (log.getEnrollment() == null) {
                continue;
            }
            Long id = log.getEnrollment().getId();
            if (log.getEventType() == SeatLeaveEventType.LEAVE) {
                open.put(id, log.getOccurredAt());
            } else {
                // 복귀든 자동 마감이든 그 이탈은 닫혔다 — 자리에 대한 표시는 사라진다
                open.remove(id);
            }
        }
        return open;
    }

    /** 기간 이탈 이력. 최근 이탈이 위로 온다. */
    public List<LeaveRow> history(AuthPrincipal me, Long academyId,
                                  LocalDate from, LocalDate to, Long classId) {
        if (from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조회 기간이 올바르지 않습니다.");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "조회 기간은 최대 %d일입니다.".formatted(MAX_RANGE_DAYS));
        }
        Long scope = me.requireAcademyScope(academyId);

        // ★ 담임은 맡은 학생만 본다 — 출결 현황과 같은 규칙이다
        var studentFilter = homeroomScopeService.resolveStudentFilter(me, (short) from.getYear(), classId);
        if (studentFilter.blocksEverything()) {
            return List.of();
        }

        Instant rangeStart = from.atStartOfDay(KST).toInstant();
        Instant rangeEnd = to.plusDays(1).atStartOfDay(KST).toInstant();
        List<SeatLeaveLog> logs = logRepository.findByAcademyBetween(
                scope, from.minusDays(1).atStartOfDay(KST).toInstant(), rangeEnd);

        Instant now = Instant.now(clock);
        Map<Long, String> classNames = new HashMap<>();

        List<LeaveRow> rows = new ArrayList<>();
        for (List<SeatLeaveLog> perStudent : groupByStudent(logs).values()) {
            SeatLeaveLog open = null;
            for (SeatLeaveLog log : perStudent) {
                if (log.getEventType() == SeatLeaveEventType.LEAVE) {
                    if (open != null) {
                        rows.add(row(open, null, Status.NO_RETURN_RECORD, null, classNames));
                    }
                    open = log;
                } else if (open != null) {
                    boolean real = log.getEventType().isRealReturn();
                    rows.add(row(open, log.getOccurredAt(),
                            real ? Status.RETURNED : Status.AUTO_CLOSED,
                            real ? minutesBetween(open.getOccurredAt(), log.getOccurredAt()) : null,
                            classNames));
                    open = null;
                }
                // 짝 없는 복귀는 이탈이 조회 범위 앞에 있던 것이다 — 보여줄 이탈 행이 없다
            }
            if (open != null) {
                rows.add(row(open, null, Status.OPEN,
                        minutesBetween(open.getOccurredAt(), now), classNames));
            }
        }

        return rows.stream()
                .filter(r -> !r.leftAt().isBefore(rangeStart))
                // 학생을 못 찾은 건은 누구 담당인지·어느 반인지 알 수 없어
                // 담임이나 반 필터에는 걸리지 않는다
                .filter(r -> studentFilter.matches(r.enrollmentId()))
                .sorted(Comparator.comparing(LeaveRow::leftAt).reversed())
                .toList();
    }

    /**
     * 학생 단위로 묶는다. 학생을 못 찾은 행은 키오스크가 보낸 카드·학번으로 묶는다 —
     * 같은 카드의 이탈·복귀는 여전히 짝이 맞아야 한다.
     */
    private Map<String, List<SeatLeaveLog>> groupByStudent(List<SeatLeaveLog> logs) {
        Map<String, List<SeatLeaveLog>> result = new LinkedHashMap<>();
        for (SeatLeaveLog log : logs) {
            String key = log.getEnrollment() != null ? "E:" + log.getEnrollment().getId()
                    : log.getRfidNo() != null ? "R:" + log.getRfidNo()
                    : "S:" + log.getStudentNo();
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(log);
        }
        return result;
    }

    private LeaveRow row(SeatLeaveLog leave, Instant closedAt, Status status, Long minutes,
                         Map<Long, String> classNames) {
        StudentEnrollment e = leave.getEnrollment();
        if (e == null) {
            return new LeaveRow(leave.getId(), null, leave.getStudentNo(), null, null,
                    leave.getAreaCd(), leave.getSeatCd(), leave.getOccurredAt(), closedAt,
                    status, minutes, false);
        }
        String className = classNames.computeIfAbsent(e.getId(), id -> classAssignmentRepository
                .findActiveFixedByEnrollmentId(id)
                .map(a -> a.getClassMaster().getName())
                .orElse(null));
        return new LeaveRow(leave.getId(), e.getId(), e.getStudentNo(), e.getStudent().getName(),
                className, leave.getAreaCd(), leave.getSeatCd(), leave.getOccurredAt(), closedAt,
                status, minutes, true);
    }

    private static long minutesBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMinutes());
    }
}
