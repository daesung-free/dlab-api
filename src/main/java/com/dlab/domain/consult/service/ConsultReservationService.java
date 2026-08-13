package com.dlab.domain.consult.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.consult.entity.ConsultReservation;
import com.dlab.domain.consult.entity.ConsultSlot;
import com.dlab.domain.consult.entity.ConsultType;
import com.dlab.domain.consult.repository.ConsultReservationRepository;
import com.dlab.domain.consult.repository.ConsultSlotRepository;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.service.NotificationCommand;
import com.dlab.domain.notification.service.NotificationService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.TeacherRepository;
import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담 예약 (F-4.11-4, 0803 답변서 신규).
 *
 * <p>흐름은 <b>담임이 가능 일정을 열고 노출 → 학생이 선택 → 담임에게 알림</b>이다.
 *
 * <p><b>★ 학생은 자기 담임 슬롯만 본다.</b> 담임은 반 배정에서 자동으로 나온다(반 담임).
 * 이 제약이 없으면 다른 반 담임에게 예약이 걸려, 그 담임이 모르는 학생을 상담하게 된다.
 * 질의응답 대면 예약과 구조는 같지만 <b>이 한 가지가 다르다</b>.
 *
 * <p><b>상담 일지와는 다른 것이다.</b> 여기는 "만나기로 한 약속"이고, {@code ConsultLog}는
 * "만나서 무슨 얘기를 했나"다. 예약 없이 쓴 일지도 있으므로(전화 상담 등) 예약 쪽이 일지를
 * 가리킨다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultReservationService {

    private final ConsultSlotRepository slotRepository;
    private final ConsultReservationRepository reservationRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final TeacherRepository teacherRepository;
    private final AccountRepository accountRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    /** 슬롯 + 예약 현황. @param reservations 담임 화면에서만 채워진다 */
    public record SlotView(ConsultSlot slot, long reserved, List<ConsultReservation> reservations) {

        public boolean isFull() {
            return reserved >= slot.getCapacity();
        }
    }

    // ── 담임: 가능 일정 개설 ──────────────────────────────────

    /**
     * 가능 일정 일괄 개설.
     *
     * <p><b>노출은 꺼진 채로 만들어진다.</b> 요구사항이 "설정 및 노출"로 두 단계다 —
     * 만들자마자 보이면 일정을 짜는 중간 상태가 학생에게 그대로 노출된다.
     *
     * <p>이미 있는 시각은 <b>건너뛴다</b>. 오전을 열어둔 뒤 오후를 추가하는 흐름이 있는데,
     * 중복이라고 통째로 거절하면 그때마다 시각을 손으로 맞춰야 한다.
     */
    @Transactional
    public List<ConsultSlot> openSlots(Teacher teacher, short year, LocalDate date,
                                       LocalTime from, LocalTime to, int intervalMinutes,
                                       short capacity, String place) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "시작 시각이 종료 시각보다 빨라야 합니다.");
        }
        if (intervalMinutes <= 0 || intervalMinutes > 240) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "간격은 1~240분 사이여야 합니다.");
        }

        Academy academy = teacher.getAcademy();
        List<ConsultSlot> created = new ArrayList<>();
        for (LocalTime start = from;
             !start.plusMinutes(intervalMinutes).isAfter(to);
             start = start.plusMinutes(intervalMinutes)) {

            if (slotRepository.existsByTeacherIdAndSlotDateAndStartTimeAndDeletedFalse(
                    teacher.getId(), date, start)) {
                continue;
            }
            created.add(slotRepository.save(new ConsultSlot(academy, year, teacher, date,
                    start, start.plusMinutes(intervalMinutes), capacity, place)));
        }
        log.info("상담 가능 일정 개설: teacher={} {} {}~{} ({}분) → {}건",
                teacher.getId(), date, from, to, intervalMinutes, created.size());
        return created;
    }

    /**
     * 노출 켜기/끄기.
     *
     * <p><b>삭제가 아니다.</b> 내려도 이미 잡힌 예약은 유효하다 — 지우면 학생 기록이 사라진다.
     */
    @Transactional
    public ConsultSlot changePublished(Long slotId, Teacher teacher, boolean published) {
        ConsultSlot slot = requireOwnSlot(slotId, teacher);
        slot.changePublished(published);
        return slot;
    }

    @Transactional
    public ConsultSlot update(Long slotId, Teacher teacher, String place, String memo) {
        ConsultSlot slot = requireOwnSlot(slotId, teacher);
        slot.update(place, memo);
        return slot;
    }

    /** 담임이 보는 자기 일정 + 예약자 명단. 노출 전 슬롯도 나온다. */
    @Transactional(readOnly = true)
    public List<SlotView> mySlots(Teacher teacher, LocalDate from, LocalDate to) {
        return withReservations(slotRepository.findByTeacher(teacher.getId(), from, to), true);
    }

    // ── 학생 ─────────────────────────────────────────────────

    /**
     * 학생이 보는 슬롯 — 자기 담임의 노출된 일정만.
     *
     * <p><b>예약자 명단은 빼고 인원 수만 준다</b> — 남의 상담 예약이 보이면 안 된다.
     */
    @Transactional(readOnly = true)
    public List<SlotView> availableSlots(StudentEnrollment enrollment,
                                         LocalDate from, LocalDate to) {
        Teacher homeroom = requireHomeroom(enrollment);
        return withReservations(
                slotRepository.findPublished(homeroom.getId(), from, to), false);
    }

    /**
     * 예약.
     *
     * <p><b>★ 행 락으로 직렬화한다.</b> "인원 세기 → 정원 비교 → INSERT"는 동시 예약에 뚫린다.
     *
     * <p>정원이 차면 거절한다 — 상담 시간은 겹칠 수 없어 대기 개념이 없다.
     */
    @Transactional
    public ConsultReservation reserve(StudentEnrollment enrollment, Long slotId,
                                      ConsultType consultType, String requestNote) {
        ConsultSlot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSULT_SLOT_NOT_FOUND));

        // ★ 자기 담임 슬롯인지 확인한다. 목록에서 걸렀더라도 slotId를 직접 넣으면 통과한다
        Teacher homeroom = requireHomeroom(enrollment);
        if (!slot.getTeacher().getId().equals(homeroom.getId())) {
            throw new BusinessException(ErrorCode.CONSULT_NOT_MY_HOMEROOM);
        }
        if (!slot.isPublished()) {
            throw new BusinessException(ErrorCode.CONSULT_SLOT_NOT_OPEN);
        }
        if (slot.isPast(LocalDate.now(clock), LocalTime.now(clock))) {
            throw new BusinessException(ErrorCode.CONSULT_SLOT_PAST);
        }
        reservationRepository
                .findBySlotIdAndEnrollmentIdAndCanceledAtIsNullAndDeletedFalse(
                        slotId, enrollment.getId())
                .ifPresent(r -> {
                    throw new BusinessException(ErrorCode.CONSULT_ALREADY_RESERVED);
                });

        if (reservationRepository.countBySlotIdAndCanceledAtIsNullAndDeletedFalse(slotId)
                >= slot.getCapacity()) {
            throw new BusinessException(ErrorCode.CONSULT_SLOT_FULL);
        }

        ConsultReservation reservation = reservationRepository.save(new ConsultReservation(
                slot, enrollment, consultType, requestNote, Instant.now(clock)));

        notifyTeacher(NotificationEvent.CONSULT_RESERVED, reservation,
                Map.of("consultType", consultType.name()));
        return reservation;
    }

    /**
     * 취소.
     *
     * <p><b>지난 타임은 취소할 수 없다.</b> 이미 지난 상담을 "안 했다"로 만들면 노쇼 여부를
     * 알 수 없다.
     *
     * @param requesterEnrollmentId 앱 경로에서만 넘긴다. 관리자 경로는 {@code null}
     */
    @Transactional
    public void cancel(Long reservationId, Long requesterEnrollmentId) {
        ConsultReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSULT_RESERVATION_NOT_FOUND));

        if (requesterEnrollmentId != null
                && !reservation.getEnrollment().getId().equals(requesterEnrollmentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!reservation.isActive()) {
            throw new BusinessException(ErrorCode.CONSULT_ALREADY_CANCELED);
        }
        if (reservation.getSlot().isPast(LocalDate.now(clock), LocalTime.now(clock))) {
            throw new BusinessException(ErrorCode.CONSULT_SLOT_PAST, "지난 상담은 취소할 수 없습니다.");
        }

        reservation.cancel(Instant.now(clock));
        // 담임이 빈 자리를 알아야 그 시간을 다시 쓸 수 있다
        notifyTeacher(NotificationEvent.CONSULT_CANCELED, reservation, Map.of());
    }

    @Transactional(readOnly = true)
    public List<ConsultReservation> myReservations(Long enrollmentId, LocalDate from, LocalDate to) {
        return reservationRepository.findMine(enrollmentId, from, to);
    }

    /** 상담을 마치고 쓴 일지를 예약에 잇는다. 이어야 노쇼(일지 없는 예약)가 구분된다. */
    @Transactional
    public ConsultReservation linkLog(Long reservationId, Long consultLogId) {
        ConsultReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSULT_RESERVATION_NOT_FOUND));
        reservation.linkLog(consultLogId);
        return reservation;
    }

    // ─────────────────────────────────────────────────────────────

    /**
     * 담임에게 알림.
     *
     * <p><b>문구는 미확정이라(I-4) 실제로는 아무것도 나가지 않는다</b> — 템플릿에 문구가 없으면
     * {@code NotificationService}가 이력만 남기고 건너뛴다. 확정되면 템플릿 행만 채우면 된다.
     *
     * <p>담임 계정이 없으면 조용히 넘어간다 — 계정 미발급 때문에 예약 자체가 실패하면 안 된다.
     */
    private void notifyTeacher(NotificationEvent event, ConsultReservation reservation,
                               Map<String, String> extra) {
        ConsultSlot slot = reservation.getSlot();
        Map<String, String> variables = new java.util.LinkedHashMap<>(extra);
        variables.put("slotDate", slot.getSlotDate().toString());
        variables.put("startTime", slot.getStartTime().toString());

        accountRepository.findByTeacherId(slot.getTeacher().getId()).ifPresent(account ->
                notificationService.send(NotificationCommand.forStudent(
                        event, account, reservation.getEnrollment().getStudent(),
                        slot.getAcademy(), slot.getYear(), variables,
                        "%s:%d".formatted(event.name(), reservation.getId()))));
    }

    private List<SlotView> withReservations(List<ConsultSlot> slots, boolean includeNames) {
        if (slots.isEmpty()) {
            return List.of();
        }
        Map<Long, List<ConsultReservation>> bySlot = reservationRepository
                .findBySlotIds(slots.stream().map(ConsultSlot::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(r -> r.getSlot().getId()));

        return slots.stream()
                .map(slot -> {
                    List<ConsultReservation> reservations =
                            bySlot.getOrDefault(slot.getId(), List.of());
                    return new SlotView(slot, reservations.size(),
                            includeNames ? reservations : List.of());
                })
                .toList();
    }

    /** 담임은 반 배정 → 반 담임으로 자동 결정된다. 미배정이면 상담을 잡을 대상이 없다. */
    private Teacher requireHomeroom(StudentEnrollment enrollment) {
        return classAssignmentRepository.findActiveFixedByEnrollmentId(enrollment.getId())
                .map(ClassAssignment::getHomeroomTeacher)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSULT_NO_HOMEROOM));
    }

    private ConsultSlot requireOwnSlot(Long slotId, Teacher teacher) {
        ConsultSlot slot = slotRepository.findById(slotId)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.CONSULT_SLOT_NOT_FOUND));

        // 남의 일정을 열고 닫으면 그 담임이 모르는 사이에 예약이 들어오거나 끊긴다
        if (!slot.getTeacher().getId().equals(teacher.getId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인의 상담 일정만 관리할 수 있습니다.");
        }
        return slot;
    }

    /** 로그인한 담임 계정 → 담당선생님. 행정({@code employee})은 상담 일정을 열 수 없다. */
    @Transactional(readOnly = true)
    public Teacher requireTeacher(Long accountId) {
        return accountRepository.findById(accountId)
                .map(a -> a.getTeacher())
                .flatMap(t -> t == null ? java.util.Optional.<Teacher>empty()
                        : teacherRepository.findById(t.getId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN,
                        "상담 일정은 담당선생님만 관리할 수 있습니다."));
    }
}
