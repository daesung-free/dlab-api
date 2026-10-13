package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.AttendanceEventType;
import com.dlab.domain.attendance.entity.AttendanceSource;
import com.dlab.domain.attendance.entity.AttendanceTaggingLog;
import com.dlab.domain.attendance.repository.AttendanceTaggingLogRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 하원 시각 수기 입력 (2026-10-13 디랩 피드백 4-2).
 *
 * <p>디랩 원문: <i>"조퇴·외출 선택을 하지 않아 하원 미표시된 경우 <b>키오스크에 알림 창이
 * 표시된 시간을 확인하고 그 시간을 수기 입력</b>합니다. 5교시 공부 후 6시에 하원하며
 * 미태그한 경우 <b>6시로 수기 입력</b>합니다."</i>
 *
 * <h2>★★ 서버가 추정하지 않는다</h2>
 * 「하원이 없는 날의 순공시간」은 오래 미확정이던 항목이고 후보가 셋이었다 —
 * ①순공 0 ②지정 하원시각까지로 간주 ③관리자 보정. <b>디랩이 ③을 골랐다.</b>
 * 그래서 <b>임의 기본값을 코드에 박을 일이 없어졌다.</b>
 *
 * <p>★ <b>넣기 전까지는 계산하지 않는다.</b> 그게 「미입력」의 정직한 표현이고,
 * 0 으로 두면 <b>진짜 0분과 구분되지 않는다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualCheckOutService {

    private final AttendanceTaggingLogRepository taggingLogRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final StudyTimeRecalculationService recalculationService;
    private final Clock clock;

    /**
     * 하원 시각을 손으로 넣는다.
     *
     * <h2>★ 등원이 없으면 거절한다</h2>
     * 하원만 있는 날은 <b>순공시간이 음수가 되거나 0 이 된다.</b> 등원을 안 찍고 간 것이면
     * 그건 결석 처리 문제이고, 하원만 끼워 넣으면 <b>「온 적 없는데 갔다」는 기록</b>이 남는다.
     *
     * <h2>★ 등원보다 이른 시각을 거절한다</h2>
     * 시각을 잘못 넣으면 순공이 음수가 되고, 그 값이 Daily Report·상벌점으로 흘러간다.
     *
     * <h2>★ 이미 하원이 있으면 그 시각을 고친다</h2>
     * 두 개를 남기면 <b>어느 것이 하원인지 모호해진다</b> — 순공 계산은 마지막 하원을 쓰므로
     * 조용히 값이 달라진다.
     *
     * @return 그날 순공시간(분). 바로 다시 계산해 돌려준다 — 화면이 보정 결과를 즉시 보여야 한다
     */
    @Transactional
    public int recordCheckOut(AuthPrincipal me, Long enrollmentId, LocalDate date,
                              LocalTime at) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        if (date.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "앞날의 하원 시각은 넣을 수 없습니다.");
        }

        var logs = taggingLogRepository.findByEnrollmentIdAndAttendanceDateOrderByRecordedAtAsc(
                enrollmentId, date);
        var arrival = logs.stream()
                .filter(l -> l.getEventType() == AttendanceEventType.CHECK_IN)
                .min(Comparator.comparing(AttendanceTaggingLog::getRecordedAt))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "등원 기록이 없는 날입니다. 하원만 넣으면 「온 적 없는데 갔다」가 됩니다 — "
                                + "결석 처리를 먼저 확인해 주세요."));

        Instant recordedAt = date.atTime(at).atZone(TimeConfig.KST).toInstant();
        if (!recordedAt.isAfter(arrival.getRecordedAt())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "하원 시각이 등원(%s)보다 빠릅니다. 순공시간이 음수가 됩니다."
                            .formatted(LocalTime.ofInstant(
                                    arrival.getRecordedAt(), TimeConfig.KST)));
        }

        var existing = logs.stream()
                .filter(l -> l.getEventType() == AttendanceEventType.CHECK_OUT)
                .max(Comparator.comparing(AttendanceTaggingLog::getRecordedAt));

        if (existing.isPresent()) {
            // ★ 두 개를 남기지 않는다 — 순공 계산이 마지막 하원을 쓰므로 조용히 값이 달라진다
            existing.get().correctRecordedAt(recordedAt);
        } else {
            taggingLogRepository.save(new AttendanceTaggingLog(
                    enrollment.getAcademy(), enrollment, AttendanceEventType.CHECK_OUT,
                    AttendanceSource.MANUAL, recordedAt, date));
        }
        taggingLogRepository.flush();

        // ★ 바로 다시 계산한다. 「보정했는데 순공이 그대로」로 보이면 담당자가 또 누른다
        int minutes = recalculationService.recalculateOne(enrollment, date);
        log.info("하원 시각 수기 입력: enrollment={}, date={}, at={}, 순공={}분, 처리자={}",
                enrollmentId, date, at, minutes, me.accountId());
        return minutes;
    }
}
