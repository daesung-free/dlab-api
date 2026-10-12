package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.StaffAttendance;
import com.dlab.domain.attendance.entity.StaffAttendanceEdit;
import com.dlab.domain.attendance.entity.StaffAttendanceType;
import com.dlab.domain.attendance.repository.StaffAttendanceRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 직원 근태 — 키오스크 출퇴근.
 *
 * <h2>출퇴근은 토글이다</h2>
 * 그날 마지막 기록의 반대를 찍는다. 기록이 없으면 출근이다. 교시로 판정하는 학생 출결과
 * 달리 <b>직원에겐 교시가 없어서</b> 시각으로 가를 근거가 없다. 하루에 여러 번 나갔다
 * 오는 경우도 이 방식이면 자연스럽게 처리된다.
 *
 * <h2>지각·초과근무를 판정하지 않는다</h2>
 * 근무시간 마스터가 있어야 하는데 요구에 없다. 지금은 <b>기록만 남긴다</b> —
 * 근태는 노동법 영역이라 판정을 임의로 넣으면 나중에 바꾸기 어렵다.
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StaffAttendanceService {

    /**
     * 재태깅 무시 창.
     *
     * <p>카드가 이중 인식되면 <b>출근이 곧바로 퇴근으로 뒤집힌다</b> — 학생 쪽보다 피해가
     * 크다(학생은 같은 상태가 한 번 더 남을 뿐이다). 창 안이면 직전 결과를 그대로 돌려준다.
     */
    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(30);

    private final StaffAttendanceRepository repository;
    private final com.dlab.domain.attendance.repository.StaffAttendanceEditRepository editRepository;
    private final com.dlab.domain.menu.repository.AccountMenuRepository accountMenuRepository;

    /**
     * 출퇴근 기록.
     *
     * @return 이번에 기록된 구분. 키오스크는 이걸 {@code att_gn}으로 받는다
     */
    @Transactional
    public StaffAttendanceType record(StudentEnrollment enrollment, LocalDateTime at) {
        if (!enrollment.getGrade().isStaff()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "직원이 아닙니다.");
        }

        LocalDate workDate = at.toLocalDate();
        Instant recordedAt = at.atZone(TimeConfig.KST).toInstant();

        StaffAttendance last = repository.findLastOfDay(enrollment.getId(), workDate)
                .orElse(null);

        // 창 안이면 새로 남기지 않는다 — 이중 인식으로 출근이 퇴근으로 뒤집히면 안 된다
        if (last != null
                && Duration.between(last.getRecordedAt(), recordedAt).abs().compareTo(DEDUP_WINDOW) < 0) {
            return last.getEventType();
        }

        StaffAttendanceType next = StaffAttendanceType.next(
                last == null ? null : last.getEventType());

        repository.save(new StaffAttendance(enrollment, workDate, next, recordedAt));
        return next;
    }

    /**
     * 관리자 조회.
     *
     * <p><b>SUPER_ADMIN 전용이다</b> — 권한은 컨트롤러가 건다. 지점 관리자에게 열면
     * 자기 지점 직원의 근태를 보게 되는데, 그건 인사 정보라 범위를 따로 정해야 한다.
     */
    public List<StaffAttendance> search(AuthPrincipal me, Long academyId, Long enrollmentId,
                                        LocalDate from, LocalDate to) {
        if (academyId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해 주세요.");
        }
        if (!me.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return repository.search(academyId, enrollmentId, from, to);
    }

    // ── 기록 보정 (긴급출입 2-4) ───────────────────────────────

    /** 수정 권한 코드. 계정에 이 권한을 켜 둔 사람만 고칠 수 있다(요구사항 3-2) */
    public static final String EDIT_MENU_CODE = "STAFF_ATTENDANCE_EDIT";

    /**
     * 출근 기록 보정.
     *
     * <h2>★ 권한이 두 층이다</h2>
     * 화면이 보이는 것({@code staff-attendance} 메뉴)과 <b>고칠 수 있는 것</b>
     * ({@code STAFF_ATTENDANCE_EDIT})이 다르다. 권한 없는 계정은 <b>이력은 보되 수정이
     * 막힌다</b> — 화면에서 버튼만 감추면 주소로 직접 부를 수 있으므로 서버가 막는다.
     *
     * <h2>★ 이유를 필수로 받는다</h2>
     * 요구사항의 「누가·언제·무엇을·왜」 중 <b>「왜」가 없으면 나머지 셋이 쓸모없어진다.</b>
     * 고친 값은 원본보다 신뢰도가 낮은데, 이유가 없으면 <b>그 사실 자체가 남지 않는다.</b>
     *
     * <h2>★ 근무일을 넘기는 수정은 거절한다</h2>
     * 시각을 다른 날로 옮기면 근무일과 어긋난 행이 생기고, 오전 출근 확인(07:05)이
     * 근무일로 세기 때문에 <b>그 행은 어느 날에도 안 잡히거나 엉뚱한 날에 잡힌다.</b>
     * 날을 옮기는 것은 지우고 새로 넣는 일이다.
     */
    @Transactional
    public StaffAttendance edit(AuthPrincipal me, Long attendanceId,
                                StaffAttendanceType eventType, LocalDateTime recordedAt,
                                String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "고친 이유를 적어 주세요. 이유가 없으면 나중에 그 값의 근거를 알 수 없습니다.");
        }
        if (!accountMenuRepository.hasMenu(me.accountId(), EDIT_MENU_CODE)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "출근 기록 수정 권한이 없습니다. 최고관리자에게 요청해 주세요.");
        }

        StaffAttendance attendance = repository.findById(attendanceId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "출근 기록을 찾을 수 없습니다."));

        if (!me.canAccessAcademy(attendance.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        Instant after = recordedAt.atZone(TimeConfig.KST).toInstant();
        if (!recordedAt.toLocalDate().equals(attendance.getWorkDate())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "근무일(%s)과 다른 날로는 옮길 수 없습니다. 지우고 새로 넣어 주세요."
                            .formatted(attendance.getWorkDate()));
        }

        StaffAttendanceType beforeType = attendance.getEventType();
        Instant beforeAt = attendance.getRecordedAt();
        if (beforeType == eventType && beforeAt.equals(after)) {
            // 바뀐 것이 없으면 이력을 남기지 않는다 — 남기면 "고친 적 있다"가 거짓이 된다
            return attendance;
        }

        attendance.applyEdit(eventType, after);
        editRepository.save(new StaffAttendanceEdit(
                attendance, beforeType, beforeAt, eventType, after, reason.trim()));

        log.info("직원 출근 기록 보정: attendanceId={}, {} {} → {} {}",
                attendanceId, beforeType, beforeAt, eventType, after);
        return attendance;
    }

    /** 그 기록의 수정 이력. 화면이 「보정됨」 표시와 상세에 쓴다. */
    @Transactional(readOnly = true)
    public List<StaffAttendanceEdit> editsOf(AuthPrincipal me, Long attendanceId) {
        StaffAttendance attendance = repository.findById(attendanceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "출근 기록을 찾을 수 없습니다."));
        if (!me.canAccessAcademy(attendance.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return editRepository.findByAttendance(attendanceId);
    }

    /** 지점·기간 수정 이력 — 관리자 웹 12번의 「이력 보기」. */
    @Transactional(readOnly = true)
    public List<StaffAttendanceEdit> editHistory(AuthPrincipal me, Long academyId,
                                                 LocalDate from, LocalDate to) {
        return editRepository.search(me.requireAcademyScope(academyId), from, to);
    }

    /**
     * 그중 고쳐진 기록의 id — 목록의 「보정됨」 표시용.
     *
     * <p>행마다 조회하면 50줄에 쿼리가 50번 나간다. 한 번에 받아 포함 여부만 본다.
     */
    @Transactional(readOnly = true)
    public java.util.Set<Long> editedIdsOf(List<StaffAttendance> records) {
        if (records.isEmpty()) {
            return java.util.Set.of();
        }
        return new java.util.HashSet<>(editRepository.findEditedIds(
                records.stream().map(StaffAttendance::getId).toList()));
    }

    /** 이 계정이 고칠 수 있나 — 화면이 버튼 노출에 쓴다(서버도 같은 값으로 막는다). */
    @Transactional(readOnly = true)
    public boolean canEdit(AuthPrincipal me) {
        return accountMenuRepository.hasMenu(me.accountId(), EDIT_MENU_CODE);
    }
}
