package com.dlab.domain.attendance.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.AttendanceDailyStatus;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceModification;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.repository.AttendanceDailyStatusRepository;
import com.dlab.domain.attendance.repository.AttendanceModificationRepository;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관리자 출결 정정 (F-4.3-1 "개별 수정").
 *
 * <h2>원장은 고치지 않는다</h2>
 * {@code attendance_tagging_log}는 <b>"키오스크에 실제로 찍혔다"는 사실 기록</b>이다.
 * 사람이 값을 바꾸면 그 사실 자체가 사라져, 나중에 "이 학생이 정말 왔었나"를 확인할
 * 방법이 없어진다. 그래서 정정은 두 가지 방식뿐이다:
 *
 * <ul>
 *   <li><b>태깅 보정</b> — {@code source=MANUAL}로 <b>새 행을 추가</b>한다.
 *       기존 {@code KIOSK_NFC} 행과 섞이지 않아 "찍은 것"과 "사람이 넣은 것"이 계속 구분된다.
 *       카드를 안 찍고 들어온 학생이 여기 해당한다</li>
 *   <li><b>상태 정정</b> — 파생 상태({@code attendance_daily_status})만 덮는다</li>
 * </ul>
 *
 * <h2>둘의 성질이 다르다</h2>
 * 태깅 보정은 원장에 남으므로 <b>배치가 다시 돌아도 같은 결과가 나온다</b> — 별도 표시가 필요 없다.
 * 상태 정정은 원장과 어긋나는 값이라 <b>표시해두지 않으면 배치가 덮어버린다</b>
 * ({@code manuallyModified}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttendanceCorrectionService {

    private final StudentEnrollmentRepository enrollmentRepository;
    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final AttendanceDailyStatusRepository dailyStatusRepository;
    private final AttendanceModificationRepository modificationRepository;
    private final DailyAttendanceConfirmService confirmService;
    private final Clock clock;

    /**
     * 태깅 누락 보정.
     *
     * <p>카드를 안 찍고 들어온 학생을 등원 처리하는 경로다. 상태를 직접 고르지 않고
     * <b>원장에 넣은 뒤 다시 판정</b>시킨다 — 등원을 넣으면 결석이 풀리고 순공시간도
     * 같이 계산된다. 관리자가 상태와 시각을 따로 입력하면 둘이 어긋난다.
     *
     * @param at 태깅 시각. 그날의 시각이며 분 단위로 받는다
     */
    @Transactional
    public void addTagging(AuthPrincipal me, Long enrollmentId, LocalDate date,
                           AttendanceEventType eventType, LocalTime at, String reason) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        requireNotFuture(date);
        requireReason(reason);

        Instant recordedAt = date.atTime(at).atZone(clock.getZone()).toInstant();
        requireNotDuplicated(enrollmentId, date, eventType, recordedAt);
        requireAfterArrival(enrollmentId, date, eventType, recordedAt);

        taggingLogRepository.save(new AttendanceTaggingLog(
                enrollment.getAcademy(), enrollment, eventType,
                AttendanceSource.MANUAL, recordedAt, date));

        modificationRepository.save(AttendanceModification.ofTaggingAdded(
                enrollment, date, eventType, recordedAt, reason));

        // 배치를 기다리면 화면이 그날 밤까지 옛 상태를 보여준다
        confirmService.confirmSingle(enrollment, date);

        log.info("출결 태깅 보정: enrollmentId={}, 일자={}, 이벤트={}, 시각={}, 처리자={}",
                enrollmentId, date, eventType, at, me.accountId());
    }

    /**
     * 하원·외출·조퇴는 <b>등원 뒤</b>여야 한다 (2026-10-13 피드백 4-2 반영).
     *
     * <h2>★ 등원이 없으면 거절한다</h2>
     * 하원만 끼워 넣으면 <b>「온 적 없는데 갔다」는 기록</b>이 남고 순공시간이 음수나 0 이
     * 된다. 등원을 안 찍고 간 것이면 그건 <b>결석 처리 문제</b>이고, 하원 보정으로 덮을
     * 일이 아니다.
     *
     * <h2>★ 등원보다 이른 시각을 거절한다</h2>
     * 순공이 음수가 되고, 그 값이 <b>Daily Report·상벌점 자동부여로 흘러간다.</b>
     * 숫자가 한 번 흘러가면 어디까지 번졌는지 되짚기 어렵다.
     *
     * <p>⚠️ <b>지각({@code LATE})도 등원이다.</b> 출결 7종 중 등원에 해당하는 것이 둘이라
     * ({@code S}등원·{@code A}지각) 하나만 보면 <b>늦게 온 학생의 하원 보정이 거절된다.</b>
     *
     * <p>등원·지각 자신과 복귀({@code RETURN})는 검사하지 않는다 — 앞의 둘은 자기 자신이고,
     * 복귀는 외출이 선행 조건이라 <b>중복 검사가 이미 막는다.</b>
     */
    private void requireAfterArrival(Long enrollmentId, LocalDate date,
                                     AttendanceEventType eventType, Instant recordedAt) {
        if (eventType != AttendanceEventType.CHECK_OUT
                && eventType != AttendanceEventType.OUTING
                && eventType != AttendanceEventType.EXCUSED_OUTING
                && eventType != AttendanceEventType.EARLY_LEAVE) {
            return;
        }

        var arrival = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(enrollmentId, date)
                .stream()
                // ★★ **지각(`A`)도 등원이다.** `CHECK_IN` 만 보면 **늦게 온 학생의 하원
                //    보정이 거절된다** — 출결 7종 중 등원에 해당하는 것이 둘이다(§3).
                //    기존 테스트가 이 실수를 잡았다
                .filter(l -> l.getEventType() == AttendanceEventType.CHECK_IN
                        || l.getEventType() == AttendanceEventType.LATE)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "등원 기록이 없는 날입니다. 등원을 먼저 넣어 주세요 — "
                                + "하원만 넣으면 「온 적 없는데 갔다」가 됩니다."));

        if (!recordedAt.isAfter(arrival.getRecordedAt())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "등원(%s)보다 빠른 시각입니다. 순공시간이 음수가 됩니다."
                            .formatted(LocalTime.ofInstant(
                                    arrival.getRecordedAt(), clock.getZone())));
        }
    }

    /**
     * 최종 상태 직접 정정.
     *
     * <p><b>태깅 보정으로 해결되는 건은 그쪽을 쓴다.</b> 여기는 원장으로 설명되지 않는
     * 정정 전용이다(예: 시스템 오류로 잘못 쌓인 태깅, 사유 승인이 늦어 무단으로 확정된 날).
     * 여기서 고친 값은 <b>배치가 더 이상 갱신하지 않으므로</b>, 이후 사유가 승인돼도
     * 자동으로 반영되지 않는다.
     */
    @Transactional
    public AttendanceDailyStatus correctStatus(AuthPrincipal me, Long enrollmentId, LocalDate date,
                                               DailyStatus status, boolean excused, String reason) {

        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        requireNotFuture(date);
        requireReason(reason);

        AttendanceDailyStatus confirmed = dailyStatusRepository
                .findByEnrollmentIdAndAttendanceDate(enrollmentId, date)
                .orElse(null);

        DailyStatus before = confirmed == null ? null : confirmed.getFinalStatus();
        Boolean beforeExcused = confirmed == null ? null : confirmed.isExcused();

        if (confirmed == null) {
            // 아직 확정 전인 날(오늘)도 고칠 수 있어야 한다 — 화면은 원장에서 파생한
            // 값을 보여주고 있고, 관리자는 그걸 고치려는 것이다
            confirmed = dailyStatusRepository.save(new AttendanceDailyStatus(
                    enrollment.getAcademy(), enrollment, date, status, excused));
        }
        confirmed.correct(status, excused, Instant.now(clock));

        modificationRepository.save(AttendanceModification.ofStatusChange(
                enrollment, date, before, beforeExcused, status, excused, reason));

        log.info("출결 상태 정정: enrollmentId={}, 일자={}, {}→{}, 사유승인={}, 처리자={}",
                enrollmentId, date, before, status, excused, me.accountId());
        return confirmed;
    }

    /** 그 학생 그 날의 정정 이력. 최신이 위다. */
    @Transactional(readOnly = true)
    public List<AttendanceModification> history(AuthPrincipal me, Long enrollmentId,
                                                LocalDate date) {
        requireEnrollment(me, enrollmentId);
        return modificationRepository
                .findByEnrollmentIdAndAttendanceDateOrderByCreatedAtDesc(enrollmentId, date);
    }

    /**
     * 같은 이벤트가 같은 시각에 이미 있으면 막는다.
     *
     * <p>등원·조퇴가 하루에 여러 번 나오는 건 정상이므로(조퇴 후 재등원) 종류만으로는
     * 막지 않는다. 막는 건 <b>버튼 두 번 누르기</b>뿐이다.
     */
    private void requireNotDuplicated(Long enrollmentId, LocalDate date,
                                      AttendanceEventType eventType, Instant at) {
        boolean exists = taggingLogRepository
                .findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(enrollmentId, date)
                .stream()
                .anyMatch(log -> log.getEventType() == eventType
                        && log.getRecordedAt().equals(at));
        if (exists) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "같은 시각에 같은 태깅이 이미 있습니다.");
        }
    }

    /**
     * 미래 날짜는 막는다.
     *
     * <p>오지 않은 날을 등원 처리하면 그날 미등원 알림이 안 나가고, 결석 확정 배치도
     * 이미 값이 있다고 보고 지나친다.
     */
    private void requireNotFuture(LocalDate date) {
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "미래 날짜는 정정할 수 없습니다.");
        }
    }

    /** 사유 없는 정정은 받지 않는다 — 이력이 "누군가 바꿨다"까지만 남으면 감사가 안 된다. */
    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "정정 사유는 필수입니다.");
        }
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }
}
