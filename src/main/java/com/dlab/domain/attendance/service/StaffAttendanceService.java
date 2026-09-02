package com.dlab.domain.attendance.service;

import com.dlab.common.config.TimeConfig;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.attendance.entity.StaffAttendance;
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
}
