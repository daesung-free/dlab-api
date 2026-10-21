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
    private final com.dlab.domain.penalty.service.PenaltyRuleEngine penaltyRuleEngine;
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

        // ★★ 여기서는 학부모 알림을 보내지 않는다 — 일부러다.
        //    보정은 **과거 일자도 된다**(미래만 막는다). 붙이면 「3일 전 18:00에 하원했습니다」가
        //    오늘 나가고, 학부모는 그게 어제 일인지 지금 일인지 알 수 없다.
        //    디랩 문구도 「태깅 시점」 발송이고, 보정은 직원이 사실을 아는 상태에서 하는 일이다.
        //
        //    ⚠️ 그래서 **당일 하원 미태그를 수기 입력하면 하원 알림이 안 간다.** 알고 둔
        //    구멍이다 — 필요해지면 「당일 보정만」으로 좁혀서 붙일 것. 통째로 붙이지 말 것.

        // 배치를 기다리면 화면이 그날 밤까지 옛 상태를 보여준다
        confirmService.confirmSingle(enrollment, date);

        // ★★ 보정으로 넣은 태깅에도 자동부여를 돌린다 (디랩 1021 피드백 2-1).
        //
        //   디랩 원문: "외출, 지각, 조퇴, 결석 입력으로 출결변경 시 벌점이 자동으로
        //   부여되거나 부여하는 버튼이 있어 직관적으로 확인이 가능하면 좋을 것 같습니다."
        //
        //   ⚠️ 지금까지 엔진은 키오스크 태깅과 일자 확정 배치에서만 돌았고,
        //   그 배치는 **결석만** 부여한다. 즉 관리자가 손으로 「결석 → 지각」으로
        //   고치면 지각 벌점이 안 붙었다. 디랩이 본 그대로다.
        //
        //   ★ 멱등키가 (학생:일자:규칙) 이라 같은 날 두 번 고쳐도 두 번 붙지 않는다.
        //   ★ REQUIRES_NEW 로 분리돼 있어 부여가 실패해도 태깅은 남는다 —
        //     보정이 롤백되면 "그 학생이 왔다는 기록" 자체가 사라진다.
        penaltyRuleEngine.apply(enrollment,
                com.dlab.domain.penalty.entity.PenaltyTriggerType.ATTENDANCE,
                eventType.getCode(), date);

        log.info("출결 태깅 보정: enrollmentId={}, 일자={}, 이벤트={}, 시각={}, 처리자={}",
                enrollmentId, date, eventType, at, me.accountId());
    }

    /**
     * 태깅 <b>삭제</b> (디랩 1021 피드백 2-1).
     *
     * <p>디랩 원문: <i>"실수로 처리된 항목은 삭제도 필요할것 같습니다."</i>
     * 지금까지 정정은 <b>추가와 덮어쓰기뿐</b>이라 잘못 넣은 태깅이 계속 쌓였다.
     *
     * <h2>★★ 사람이 넣은 행만 지운다 — 키오스크 태깅은 지우지 않는다</h2>
     * 이 클래스 주석의 원칙(<i>"원장은 고치지 않는다 — 사람이 값을 바꾸면 '이 학생이
     * 정말 왔었나'를 확인할 방법이 없어진다"</i>)은 <b>키오스크 태깅에 대한 것이다.</b>
     * {@code MANUAL} 행은 애초에 사실 기록이 아니라 <b>사람의 입력</b>이고, 오타를
     * 영구 보존할 이유가 없다.
     *
     * <h2>★ 조퇴 취소가 이 경로다</h2>
     * 조퇴는 하루의 종료 경계라 그 뒤 복귀로 재실이 다시 열리지 않는다
     * ({@code StudyTimeCalculator}). 조퇴 뒤 다시 들어온 학생은 <b>조퇴를 지워야</b>
     * 맞고, 그 경로가 없으면 담당자가 할 수 있는 일이 없었다.
     *
     * <h2>★ 수정 API 를 따로 만들지 않는다</h2>
     * 「삭제 후 재입력」으로 충분하다. 수정을 따로 두면 <b>이력이 두 갈래가 된다</b> —
     * 같은 태깅에 대해 「고쳤다」와 「지우고 넣었다」가 섞여 되짚기 어려워진다.
     */
    @Transactional
    public void deleteTagging(AuthPrincipal me, Long enrollmentId, Long taggingId, String reason) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        requireReason(reason);

        AttendanceTaggingLog tagging = taggingLogRepository.findById(taggingId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "태깅 기록이 없습니다."));

        // ★ 다른 학생의 태깅 id 를 넣어도 지워지지 않게 한다 — 지점 권한만 보면 통과한다
        if (!tagging.getEnrollment().getId().equals(enrollmentId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "그 학생의 태깅 기록이 아닙니다.");
        }
        if (tagging.isDeleted()) {
            return;   // 두 번 눌러도 안전하다
        }
        // ★★ 키오스크 태깅은 사실 기록이라 지우지 않는다. 잘못 찍힌 건은 상태 정정으로 간다
        if (tagging.getSource() != AttendanceSource.MANUAL) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "키오스크로 찍힌 태깅은 지울 수 없습니다. 상태 정정을 쓰세요 — "
                            + "원장을 지우면 그 학생이 정말 왔었는지 확인할 방법이 없어집니다.");
        }

        tagging.markDeleted();
        modificationRepository.save(AttendanceModification.ofTaggingRemoved(
                enrollment, tagging.getAttendanceDate(),
                tagging.getEventType(), tagging.getRecordedAt(), reason));

        // ★ 지운 뒤 다시 판정한다. 안 하면 그날 상태·순공시간에 옛 값이 그대로 남는다
        confirmService.confirmSingle(enrollment, tagging.getAttendanceDate());

        // ★★ 그 태깅으로 붙은 자동 벌점을 거둔다 (디랩 1021 피드백 2-1 「자동 취소」).
        //   지각 태깅을 지웠는데 지각 벌점이 남으면, 담당자는 벌점 화면에서 그걸 또
        //   손으로 취소해야 하고 **왜 남았는지 알 수 없다.**
        //   ⚠️ 수기 부여분은 건드리지 않는다 — 멱등키가 없는 행은 조회에 안 걸린다.
        //     사람이 판단해 준 것을 출결 정정이 거두면 안 된다.
        int revoked = penaltyRuleEngine.revokeAuto(enrollment,
                com.dlab.domain.penalty.entity.PenaltyTriggerType.ATTENDANCE,
                tagging.getEventType().getCode(), tagging.getAttendanceDate());
        if (revoked > 0) {
            log.info("태깅 삭제로 자동 벌점 {}건 취소: enrollmentId={}, 이벤트={}",
                    revoked, enrollmentId, tagging.getEventType());
        }

        log.info("출결 태깅 삭제: enrollmentId={}, taggingId={}, 이벤트={}, 처리자={}",
                enrollmentId, taggingId, tagging.getEventType(), me.accountId());
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

        syncAbsencePenalty(enrollment, date, before, beforeExcused, status, excused);

        log.info("출결 상태 정정: enrollmentId={}, 일자={}, {}→{}, 사유승인={}, 처리자={}",
                enrollmentId, date, before, status, excused, me.accountId());
        return confirmed;
    }

    /**
     * 상태 정정에 맞춰 <b>결석 벌점</b>을 부여·취소한다 (디랩 1021 피드백 2-1).
     *
     * <p>디랩 원문: <i>"출결이 수정될 경우 벌점도 <b>자동 부여되거나, 자동 취소</b>가
     * 가능하면 좋을것 같습니다."</i>
     *
     * <h2>★ 여기서 다루는 것은 결석뿐이다</h2>
     * 지각·조퇴·외출은 <b>태깅이 있어야 성립</b>하고, 그 벌점은 태깅 경로
     * ({@code addTagging}·키오스크)가 붙인다. 상태만 덮는 이 경로에서 태깅 없는 지각
     * 벌점을 주면 <b>원장에 근거가 없는 벌점</b>이 생긴다.
     *
     * <p>결석은 다르다 — <b>「태깅이 없었다」</b>라 원장으로 표현되지 않고
     * 일자 상태로만 존재한다. 그래서 상태 전이를 보고 처리한다.
     *
     * <h2>★★ 사유 인정이 결석 벌점을 가른다</h2>
     * 배치가 {@code status == ABSENT && !excused} 일 때만 부여한다 — 사유가 승인된
     * 결석은 벌점이 없다. 정정도 같은 규칙을 따라야 하고, 그래서
     * <b>「무단결석 → 사유결석」으로만 바꿔도 벌점을 거둬야 한다</b>(상태는 그대로다).
     *
     * <p>⚠️ <b>과거 날짜를 고치면 그달 벌점이 바뀐다.</b> 이미 제적 판정(40점)이 났을
     * 수 있다 — 정정 이력에 남으므로 추적은 되지만, 화면이 경고를 띄워야 한다.
     */
    private void syncAbsencePenalty(StudentEnrollment enrollment, LocalDate date,
                                    DailyStatus before, Boolean beforeExcused,
                                    DailyStatus after, boolean afterExcused) {
        boolean wasPenalized = before == DailyStatus.ABSENT && Boolean.FALSE.equals(beforeExcused);
        boolean nowPenalized = after == DailyStatus.ABSENT && !afterExcused;

        if (wasPenalized == nowPenalized) {
            return;
        }
        var trigger = com.dlab.domain.penalty.entity.PenaltyTriggerType.ATTENDANCE;
        String condition = com.dlab.domain.penalty.entity.TriggerCondition.ABSENT;

        if (nowPenalized) {
            penaltyRuleEngine.apply(enrollment, trigger, condition, date);
            log.info("정정으로 결석 벌점 부여: enrollmentId={}, 일자={}",
                    enrollment.getId(), date);
        } else {
            int revoked = penaltyRuleEngine.revokeAuto(enrollment, trigger, condition, date);
            log.info("정정으로 결석 벌점 {}건 취소: enrollmentId={}, 일자={}",
                    revoked, enrollment.getId(), date);
        }
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
