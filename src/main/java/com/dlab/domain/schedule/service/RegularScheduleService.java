package com.dlab.domain.schedule.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.schedule.entity.RegularSchedule;
import com.dlab.domain.schedule.entity.RegularScheduleItem;
import com.dlab.domain.schedule.entity.ScheduleSource;
import com.dlab.domain.schedule.repository.RegularScheduleRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 정기일정 (F-4.1-7, 앱 A-7).
 *
 * <p>현강·과외처럼 매주 같은 요일에 나갔다 오는 외부 일정을 <b>월 단위로 미리</b> 등록한다.
 * 승인되면 그 시간의 외출이 무단이 아니게 되어 벌점을 받지 않는다.
 *
 * <h2>2트랙 (0803)</h2>
 * 학생이 앱에서 내면 승인 라우팅을 타고, <b>담임이 웹에서 대신 넣으면 자동 승인</b>이다 —
 * 승인자가 곧 등록자라 자기가 넣고 자기가 승인하는 절차를 만들 이유가 없다.
 *
 * <h2>★ 중복 규칙이 미확정이다 (I-27)</h2>
 * 관리자 등록분과 학생 등록분의 병합 규칙이 안 정해졌다. 그래서 <b>어느 쪽도 이기게 하지
 * 않는다</b> — 같은 달에 이미 제출이 있으면 거절하고 사람이 판단하게 둔다. 규칙 없이
 * 자동으로 하나를 지우면 담임이 넣은 일정이 학생 등록으로 조용히 덮인다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegularScheduleService {

    private final RegularScheduleRepository scheduleRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ApprovalService approvalService;
    private final Clock clock;

    /** 화면이 보내는 일정 한 줄. */
    public record ItemInput(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
                            String title, String place) {
    }

    // ── 등록 ──────────────────────────────────────────────────

    /**
     * 학생 앱 등록 — 승인 라우팅을 탄다.
     *
     * <p><b>지난 달에는 낼 수 없다.</b> 정기일정은 "앞으로 이 시간에 나갑니다"라는 신고라
     * 이미 지난 날의 외출을 사후에 인정받는 통로가 되면 안 된다. 사후 처리는 사유신청이다.
     */
    @Transactional
    public RegularSchedule submitByStudent(StudentEnrollment enrollment, short month,
                                           List<ItemInput> items) {
        RegularSchedule schedule = create(enrollment, month, ScheduleSource.STUDENT, items);

        ApprovalRequest approval =
                approvalService.create(enrollment, RequestType.REGULAR_SCHEDULE);
        schedule.linkApproval(approval);
        return schedule;
    }

    /** 담임 대신 등록 — 자동 승인이라 승인 요청을 만들지 않는다. */
    @Transactional
    public RegularSchedule registerByAdmin(AuthPrincipal me, Long enrollmentId, short month,
                                           List<ItemInput> items) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        return create(enrollment, month, ScheduleSource.ADMIN, items);
    }

    private RegularSchedule create(StudentEnrollment enrollment, short month,
                                   ScheduleSource source, List<ItemInput> items) {
        verifyMonth(month);
        verifyItems(items);

        scheduleRepository
                .findByStudentAndMonth(enrollment.getId(), enrollment.getYear(), month)
                .ifPresent(existing -> {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "이미 등록된 일정이 있습니다. 수정으로 변경해 주세요.");
                });

        RegularSchedule schedule = new RegularSchedule(enrollment, month, source);
        items.forEach(i -> schedule.addItem(toItem(i)));
        return scheduleRepository.save(schedule);
    }

    // ── 수정 ──────────────────────────────────────────────────

    /**
     * 줄을 통째로 갈아끼운다 — 화면이 편집한 전체 목록을 그대로 보낸다.
     *
     * <p><b>학생이 고치면 승인을 다시 받는다.</b> 승인된 일정의 시간을 바꾸고도 승인이
     * 유지되면, 학부모가 승인한 적 없는 시간대에 외출이 인정된다. 관리자 등록분은
     * 애초에 자동 승인이라 다시 받을 것이 없다.
     */
    @Transactional
    public RegularSchedule replaceItems(Long scheduleId, List<ItemInput> items) {
        verifyItems(items);
        RegularSchedule schedule = require(scheduleId);
        schedule.replaceItems(items.stream().map(this::toItem).toList());

        if (schedule.getSource() == ScheduleSource.STUDENT) {
            if (schedule.getApprovalRequest() != null) {
                approvalService.cancelByRequester(schedule.getApprovalRequest().getId());
            }
            schedule.linkApproval(approvalService.create(
                    schedule.getEnrollment(), RequestType.REGULAR_SCHEDULE));
        }
        return schedule;
    }

    @Transactional
    public void delete(Long scheduleId) {
        RegularSchedule schedule = require(scheduleId);
        if (schedule.getApprovalRequest() != null) {
            approvalService.cancelByRequester(schedule.getApprovalRequest().getId());
        }
        schedule.markDeleted();
    }

    // ── 조회 ──────────────────────────────────────────────────

    public Optional<RegularSchedule> findMonth(Long enrollmentId, short year, short month) {
        return scheduleRepository.findByStudentAndMonth(enrollmentId, year, month);
    }

    public List<RegularSchedule> findByAcademy(Long academyId, short year, short month) {
        return scheduleRepository.findByAcademyAndMonth(academyId, year, month);
    }

    /**
     * 그날 적용되는 일정 줄.
     *
     * <p><b>승인된 것만 나온다</b> — 대기중인 일정으로 외출을 인정하면 승인 절차가 무의미해진다.
     * 인정 판정({@code ScheduleComplianceService})이 이걸 쓴다.
     */
    public List<RegularScheduleItem> itemsOn(Long enrollmentId, LocalDate date) {
        return scheduleRepository
                .findByStudentAndMonth(enrollmentId, (short) date.getYear(),
                        (short) date.getMonthValue())
                .filter(RegularSchedule::isApproved)
                .map(s -> s.getItems().stream()
                        .filter(i -> !i.isDeleted())
                        .filter(i -> i.dayOfWeekValue() == date.getDayOfWeek())
                        .toList())
                .orElse(List.of());
    }

    // ─────────────────────────────────────────────────────────

    private RegularSchedule require(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "정기일정을 찾을 수 없습니다."));
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

    /** 지난 달은 막는다. 사후 인정 통로가 되면 사유신청이 존재할 이유가 없어진다. */
    private void verifyMonth(short month) {
        LocalDate today = LocalDate.now(clock);
        if (month < 1 || month > 12) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "월이 올바르지 않습니다.");
        }
        if (month < today.getMonthValue()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지난 달 일정은 등록할 수 없습니다.");
        }
    }

    /** 같은 요일에 시간이 겹치는 줄은 막는다 — 어느 줄로 판정할지 정할 수 없다. */
    private void verifyItems(List<ItemInput> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "일정을 한 건 이상 넣어주세요.");
        }
        List<RegularScheduleItem> converted = items.stream().map(this::toItem).toList();
        for (int i = 0; i < converted.size(); i++) {
            for (int j = i + 1; j < converted.size(); j++) {
                if (converted.get(i).overlaps(converted.get(j))) {
                    throw new BusinessException(ErrorCode.INVALID_REQUEST,
                            "같은 요일에 시간이 겹치는 일정이 있습니다.");
                }
            }
        }
    }

    private RegularScheduleItem toItem(ItemInput input) {
        if (input.endTime() == null || input.startTime() == null
                || !input.endTime().isAfter(input.startTime())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "종료 시각은 시작 시각보다 뒤여야 합니다.");
        }
        return new RegularScheduleItem(input.dayOfWeek(), input.startTime(),
                input.endTime(), input.title(), input.place());
    }
}
