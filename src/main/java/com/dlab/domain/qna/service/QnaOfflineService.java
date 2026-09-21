package com.dlab.domain.qna.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.qna.entity.QnaOfflineReservation;
import com.dlab.domain.qna.entity.QnaOfflineSlot;
import com.dlab.domain.qna.repository.QnaOfflineReservationRepository;
import com.dlab.domain.qna.repository.QnaOfflineSlotRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.TeacherRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 질의응답 — 대면(OFF) (F-4.11-7 · A-13).
 *
 * <p>흐름은 <b>상담실 가능 타임 개설 → 학생이 조회하고 예약 → 취소</b>다.
 *
 * <p><b>온라인(ON)은 만들지 않았다.</b> 시트가 스스로 미확정이라고 적어놨다 —
 * *"멘토 배정 규칙·답변 SLA·첨부 허용 여부 확정 필요"*,
 * *"1:1채팅과 기능 경계 선결정 필요(별도 도메인 유지 vs 채팅 흡수)"*.
 * ▷[0803] 회신에도 *"온라인은 현재 미운영, 추후 대비"*로 되어 있다.
 *
 * <p>⚠️ 시트의 {@code ON}/{@code OFF}는 켜짐/꺼짐이 아니라 <b>온라인/오프라인</b>의 약칭이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QnaOfflineService {

    private final QnaOfflineSlotRepository slotRepository;
    private final QnaOfflineReservationRepository reservationRepository;
    private final AcademyRepository academyRepository;
    private final TeacherRepository teacherRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final com.dlab.domain.user.repository.ClassAssignmentRepository classAssignmentRepository;
    private final Clock clock;
    private final com.dlab.domain.file.service.FileAttachmentService fileAttachmentService;

    /** 첨부 대상 종류. 사진은 비공개 버킷에 둔다 — 문제 사진에 이름·학교가 찍혀 있을 수 있다. */
    public static final String PHOTO_OWNER = "QNA_RESERVATION";

    /** 예약 한 건에 붙일 수 있는 사진 수. 문제 몇 장이면 충분하고, 무제한이면 저장소가 샌다. */
    static final int MAX_PHOTOS = 3;

    /**
     * 슬롯 + 예약 현황.
     *
     * @param reserved 유효 예약 수. 정원과 비교해 앱이 "마감"을 표시한다
     */
    /**
     * @param classNames 예약자 등록ID → 반 이름. <b>앱 응답에서는 비어 있다</b>
     *                   (남의 예약 자체가 안 내려간다). 담당 교사가 "어느 반 누가
     *                   물어보는지"를 목록에서 훑는 화면이라 관리자 쪽에만 채운다
     */
    public record SlotView(QnaOfflineSlot slot, long reserved,
                           List<QnaOfflineReservation> reservations,
                           Map<Long, String> classNames) {

        public SlotView(QnaOfflineSlot slot, long reserved,
                        List<QnaOfflineReservation> reservations) {
            this(slot, reserved, reservations, Map.of());
        }

        public boolean isFull() {
            return reserved >= slot.getCapacity();
        }

        public String classNameOf(QnaOfflineReservation r) {
            return classNames.get(r.getEnrollment().getId());
        }
    }

    // ── 개설 (F-4.11-7) ──────────────────────────────────────────

    /**
     * 가능 타임 일괄 개설.
     *
     * <p><b>★ 간격을 여기서 받는다.</b> ▷[0803] *"현재 15분 간격, 변동 가능"*이라 간격을
     * 스키마에 두지 않았다 — 시작·종료·간격을 받아 슬롯 행을 여러 개 만든다.
     * 간격이 바뀌어도 데이터만 달라지고 테이블은 그대로다.
     *
     * <p>이미 있는 시각은 <b>건너뛴다</b>. 오전에 열어둔 뒤 오후를 추가로 여는 흐름이 있는데,
     * 중복이라고 통째로 거절하면 그때마다 시각을 손으로 맞춰야 한다.
     *
     * @return 새로 만든 슬롯
     */
    @Transactional
    public List<QnaOfflineSlot> openSlots(Long academyId, short year, LocalDate date,
                                          LocalTime from, LocalTime to, int intervalMinutes,
                                          Long teacherId, String room, short capacity,
                                          AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (from == null || to == null || !from.isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "시작 시각이 종료 시각보다 빨라야 합니다.");
        }
        if (intervalMinutes <= 0 || intervalMinutes > 240) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "간격은 1~240분 사이여야 합니다.");
        }

        Academy academy = academyRepository.findById(academyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        Teacher teacher = teacherId == null ? null
                : teacherRepository.findById(teacherId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND));

        List<QnaOfflineSlot> created = new ArrayList<>();
        for (LocalTime start = from;
             !start.plusMinutes(intervalMinutes).isAfter(to);
             start = start.plusMinutes(intervalMinutes)) {

            if (exists(academyId, date, start, room)) {
                continue;
            }
            created.add(slotRepository.save(new QnaOfflineSlot(
                    academy, year, date, start, start.plusMinutes(intervalMinutes),
                    teacher, room, capacity)));
        }
        log.info("상담 슬롯 개설: {} {}~{} ({}분 간격) → {}건",
                date, from, to, intervalMinutes, created.size());
        return created;
    }

    /** 날짜별 슬롯 + 예약 현황 — 관리자 예약 현황 화면. */
    @Transactional(readOnly = true)
    public List<SlotView> slotsWithReservations(Long academyId, LocalDate date,
                                                AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        return withReservations(slotRepository.findByDate(academyId, date));
    }

    /**
     * 기간 슬롯 + 예약 현황.
     *
     * <p>화면이 <b>주간 그리드</b>라 하루씩 부르면 5회가 매번 나간다. 예약은 슬롯 ID를
     * 모아 한 번에 읽으므로, 기간이 늘어도 쿼리는 2개로 고정된다.
     */
    @Transactional(readOnly = true)
    public List<SlotView> slotsWithReservations(Long academyId, LocalDate from, LocalDate to,
                                                AuthPrincipal principal) {
        verifyAccess(academyId, principal);
        if (to.isBefore(from)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "종료일이 시작일보다 빠릅니다.");
        }
        if (from.plusDays(MAX_RANGE_DAYS).isBefore(to)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "조회 기간은 최대 %d일입니다.".formatted(MAX_RANGE_DAYS));
        }
        return withReservations(slotRepository.findByDateRange(academyId, from, to));
    }

    private List<SlotView> withReservations(List<QnaOfflineSlot> slots) {
        if (slots.isEmpty()) {
            return List.of();
        }
        List<QnaOfflineReservation> all = reservationRepository
                .findBySlotIds(slots.stream().map(QnaOfflineSlot::getId).toList());

        Map<Long, List<QnaOfflineReservation>> bySlot = all.stream()
                .collect(Collectors.groupingBy(r -> r.getSlot().getId()));

        // 예약자마다 반을 조회하면 쿼리가 인원수만큼 나간다. 한 번에 받아 붙인다
        Map<Long, String> classNames = new java.util.HashMap<>();
        List<Long> enrollmentIds = all.stream()
                .map(r -> r.getEnrollment().getId()).distinct().toList();
        if (!enrollmentIds.isEmpty()) {
            classAssignmentRepository.findActiveFixedByEnrollmentIds(enrollmentIds)
                    .forEach(ca -> classNames.put(ca.getEnrollment().getId(),
                            ca.getClassMaster().getName()));
        }

        return slots.stream()
                .map(slot -> {
                    List<QnaOfflineReservation> reservations =
                            bySlot.getOrDefault(slot.getId(), List.of());
                    return new SlotView(slot, reservations.size(), reservations, classNames);
                })
                .toList();
    }

    /** 주간 화면이 기본이라 한 달이면 충분하다. 열어두면 전 기간 조회가 들어온다. */
    private static final int MAX_RANGE_DAYS = 62;

    /**
     * 슬롯 닫기/열기.
     *
     * <p><b>삭제가 아니다.</b> 지우면 이미 예약한 학생의 기록이 사라진다 —
     * 닫으면 새 예약만 안 받고 기존 예약은 그대로 유효하다.
     */
    @Transactional
    public QnaOfflineSlot changeClosed(Long slotId, boolean closed, AuthPrincipal principal) {
        QnaOfflineSlot slot = requireSlot(slotId, principal);
        slot.changeClosed(closed);
        return slot;
    }

    @Transactional
    public QnaOfflineSlot assign(Long slotId, Long teacherId, String room, String memo,
                                 AuthPrincipal principal) {
        QnaOfflineSlot slot = requireSlot(slotId, principal);
        Teacher teacher = teacherId == null ? null
                : teacherRepository.findById(teacherId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EMPLOYEE_NOT_FOUND));
        slot.assign(teacher, room, memo);
        return slot;
    }

    // ── 앱 (A-13) ────────────────────────────────────────────────

    /** 학생이 보는 그날 슬롯. 예약자 명단은 빼고 <b>인원 수만</b> 준다 — 남의 예약이 보이면 안 된다. */
    @Transactional(readOnly = true)
    public List<SlotView> availableSlots(Long academyId, LocalDate date) {
        List<QnaOfflineSlot> slots = slotRepository.findByDate(academyId, date);
        return slots.stream()
                .map(slot -> new SlotView(slot,
                        reservationRepository.countBySlotIdAndCanceledAtIsNullAndDeletedFalse(
                                slot.getId()),
                        List.of()))
                .toList();
    }

    /**
     * 예약.
     *
     * <p><b>★ 행 락으로 직렬화한다.</b> "인원 세기 → 정원 비교 → INSERT"는 동시 예약에 뚫린다 —
     * 특강 정원에서 같은 처리를 했고, 그 전에 기숙사에서 실제로 뚫린 적이 있다.
     *
     * <p>정원이 차면 <b>거절한다</b>. 특강과 달리 대기 개념이 없다 — 상담 시간은 겹칠 수 없다.
     */
    @Transactional
    public QnaOfflineReservation reserve(Long slotId, Long enrollmentId, String question,
                                         String subject) {
        return reserve(slotId, enrollmentId, question).withSubject(subject);
    }

    @Transactional
    public QnaOfflineReservation reserve(Long slotId, Long enrollmentId, String question) {
        QnaOfflineSlot slot = slotRepository.findByIdForUpdate(slotId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QNA_SLOT_NOT_FOUND));

        LocalDate today = LocalDate.now(clock);
        if (slot.isPast(today, LocalTime.now(clock))) {
            throw new BusinessException(ErrorCode.QNA_SLOT_PAST);
        }
        if (slot.isClosed()) {
            throw new BusinessException(ErrorCode.QNA_SLOT_CLOSED);
        }
        reservationRepository
                .findBySlotIdAndEnrollmentIdAndCanceledAtIsNullAndDeletedFalse(slotId, enrollmentId)
                .ifPresent(r -> {
                    throw new BusinessException(ErrorCode.QNA_ALREADY_RESERVED);
                });

        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        // 다른 지점 상담실을 예약하면 명단이 섞인다
        if (!enrollment.getAcademy().getId().equals(slot.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }

        long reserved = reservationRepository
                .countBySlotIdAndCanceledAtIsNullAndDeletedFalse(slotId);
        if (reserved >= slot.getCapacity()) {
            throw new BusinessException(ErrorCode.QNA_SLOT_FULL);
        }

        return reservationRepository.save(new QnaOfflineReservation(
                slot, enrollment, question, Instant.now(clock)));
    }

    /**
     * 취소.
     *
     * <p><b>지난 타임은 취소할 수 없다.</b> 이미 지난 상담을 "안 했다"로 만들면
     * 노쇼 여부를 알 수 없다.
     */
    @Transactional
    public void cancel(Long reservationId, Long requesterEnrollmentId) {
        QnaOfflineReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QNA_RESERVATION_NOT_FOUND));

        // 앱에서 남의 예약을 취소하지 못하게. 관리자 경로는 null을 넘긴다.
        if (requesterEnrollmentId != null
                && !reservation.getEnrollment().getId().equals(requesterEnrollmentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        if (!reservation.isActive()) {
            throw new BusinessException(ErrorCode.QNA_ALREADY_CANCELED);
        }
        if (reservation.getSlot().isPast(LocalDate.now(clock), LocalTime.now(clock))) {
            throw new BusinessException(ErrorCode.QNA_SLOT_PAST, "지난 상담은 취소할 수 없습니다.");
        }
        reservation.cancel(Instant.now(clock));
    }

    /**
     * 질문 사진 첨부 — 본인 예약에만, 지나지 않은 활성 예약에만, 최대 3장.
     *
     * <p>비공개로 올린다. 문제 사진에 이름·학교·수험번호가 찍혀 있는 일이 흔하다.
     */
    @Transactional
    public com.dlab.domain.file.entity.FileAttachment addPhoto(Long reservationId, Long enrollmentId,
                                                               String originalName, String contentType,
                                                               byte[] content) {
        QnaOfflineReservation reservation = requireMine(reservationId, enrollmentId);
        if (!reservation.isActive()) {
            throw new BusinessException(ErrorCode.QNA_ALREADY_CANCELED);
        }
        if (reservation.getSlot().isPast(LocalDate.now(clock), LocalTime.now(clock))) {
            throw new BusinessException(ErrorCode.QNA_SLOT_PAST);
        }
        if (fileAttachmentService.findByOwner(PHOTO_OWNER, reservationId).size() >= MAX_PHOTOS) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "사진은 예약 한 건에 %d장까지 올릴 수 있습니다.".formatted(MAX_PHOTOS));
        }
        return fileAttachmentService.upload(com.dlab.domain.file.entity.FileVisibility.PRIVATE,
                PHOTO_OWNER, reservationId, originalName, contentType, content);
    }

    /** 질문 사진 삭제 — 본인 예약의 사진만. */
    @Transactional
    public void deletePhoto(Long reservationId, Long enrollmentId, Long attachmentId) {
        requireMine(reservationId, enrollmentId);
        boolean mine = fileAttachmentService.findByOwner(PHOTO_OWNER, reservationId).stream()
                .anyMatch(f -> f.getId().equals(attachmentId));
        if (!mine) {
            throw new BusinessException(ErrorCode.FILE_NOT_FOUND);
        }
        fileAttachmentService.delete(attachmentId);
    }

    /**
     * 예약별 사진 — 보는 주소까지 붙인다. 주소는 짧게 유효한 presigned URL 이라 화면에 오래 두지 말 것.
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, List<Photo>> photosOf(java.util.Collection<Long> reservationIds) {
        java.util.Map<Long, List<Photo>> result = new java.util.HashMap<>();
        for (Long id : reservationIds) {
            List<Photo> photos = fileAttachmentService.findByOwner(PHOTO_OWNER, id).stream()
                    .map(f -> new Photo(f.getId(), f.getOriginalName(),
                            fileAttachmentService.url(f.getId())))
                    .toList();
            if (!photos.isEmpty()) {
                result.put(id, photos);
            }
        }
        return result;
    }

    public record Photo(Long attachmentId, String name, String url) {
    }

    private QnaOfflineReservation requireMine(Long reservationId, Long enrollmentId) {
        QnaOfflineReservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new BusinessException(ErrorCode.QNA_RESERVATION_NOT_FOUND));
        if (!reservation.getEnrollment().getId().equals(enrollmentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN);
        }
        return reservation;
    }

    /** 학생 본인 예약 내역. 취소분도 이력으로 나온다. */
    @Transactional(readOnly = true)
    public List<QnaOfflineReservation> myReservations(Long enrollmentId,
                                                      LocalDate from, LocalDate to) {
        return reservationRepository.findMine(enrollmentId, from, to);
    }

    // ─────────────────────────────────────────────────────────────

    private boolean exists(Long academyId, LocalDate date, LocalTime start, String room) {
        return room == null
                ? slotRepository.existsByAcademyIdAndSlotDateAndStartTimeAndRoomIsNullAndDeletedFalse(
                        academyId, date, start)
                : slotRepository.existsByAcademyIdAndSlotDateAndStartTimeAndRoomAndDeletedFalse(
                        academyId, date, start, room);
    }

    private QnaOfflineSlot requireSlot(Long slotId, AuthPrincipal principal) {
        QnaOfflineSlot slot = slotRepository.findById(slotId)
                .filter(s -> !s.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.QNA_SLOT_NOT_FOUND));
        verifyAccess(slot.getAcademy().getId(), principal);
        return slot;
    }

    private void verifyAccess(Long academyId, AuthPrincipal principal) {
        if (principal != null && !principal.canAccessAcademy(academyId)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }
}
