package com.dlab.api.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.consult.entity.ConsultReservation;
import com.dlab.domain.consult.entity.ConsultSlot;
import com.dlab.domain.consult.entity.ConsultType;
import com.dlab.domain.consult.service.ConsultReservationService;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.repository.NotificationLogRepository;
import com.dlab.domain.user.entity.*;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상담 예약 (F-4.11-4, 0803 답변서 신규).
 *
 * <p>담임이 가능 일정을 열고 노출하면 학생이 고른다. 질의응답 대면 예약과 구조는 같지만
 * <b>학생이 자기 담임 슬롯만 예약할 수 있다</b>는 점이 다르다.
 */
@SpringBootTest
@Transactional
class ConsultReservationTest {

    @Autowired ConsultReservationService consultService;
    @Autowired NotificationLogRepository notificationLogRepository;
    @Autowired EntityManager em;
    @Autowired Clock clock;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy bundang;
    Teacher homeroom;
    Teacher otherTeacher;
    StudentEnrollment minji;
    LocalDate tomorrow;

    @BeforeEach
    void setUp() {
        bundang = new Academy("31", "분당", LocalTime.of(9, 0));
        em.persist(bundang);

        homeroom = new Teacher(bundang, "김담임", "010-9999-0001");
        em.persist(homeroom);
        em.persist(Account.forTeacher(homeroom, "teacher-hr", "hash"));

        otherTeacher = new Teacher(bundang, "박담임", "010-9999-0002");
        em.persist(otherTeacher);

        Student student = new Student("DL-2026-0419", "김민지", "010-1111-2222");
        em.persist(student);
        minji = new StudentEnrollment(student, bundang, (short) 2026,
                "2026-0001", null, GradeType.HIGH3);
        em.persist(minji);

        tomorrow = LocalDate.now(clock).plusDays(1);
        em.flush();
    }

    /** 반 배정이 곧 담임 지정이다 — 학생별 상담 담당을 따로 고르는 화면은 없다. */
    private void assignHomeroom(StudentEnrollment enrollment, Teacher teacher) {
        ClassMaster clazz = new ClassMaster(bundang, (short) 2026,
                "고3-" + enrollment.getStudentNo(), ClassType.FIXED, teacher);
        em.persist(clazz);
        em.persist(new ClassAssignment(bundang, enrollment, clazz, ClassType.FIXED));
        em.flush();
    }

    private ConsultSlot openOne(Teacher teacher, int hour) {
        List<ConsultSlot> slots = consultService.openSlots(teacher, (short) 2026, tomorrow,
                LocalTime.of(hour, 0), LocalTime.of(hour, 30), 30, (short) 1, "상담실1");
        em.flush();
        return slots.get(0);
    }

    private ConsultSlot openAndPublish(Teacher teacher, int hour) {
        ConsultSlot slot = openOne(teacher, hour);
        consultService.changePublished(slot.getId(), teacher, true);
        em.flush();
        return slot;
    }

    // ── 개설·노출 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 개설한 일정은 꺼진 채로 만들어진다 — 짜는 중간 상태가 학생에게 보이면 안 된다")
    void slotsStartUnpublished() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openOne(homeroom, 14);

        assertThat(slot.isPublished()).isFalse();
        assertThat(consultService.availableSlots(minji, tomorrow, tomorrow)).isEmpty();

        consultService.changePublished(slot.getId(), homeroom, true);
        assertThat(consultService.availableSlots(minji, tomorrow, tomorrow)).hasSize(1);
    }

    @Test
    @DisplayName("간격만큼 슬롯이 쪼개지고, 다시 열면 이미 있는 시각은 건너뛴다")
    void splitsByIntervalAndSkipsExisting() {
        List<ConsultSlot> first = consultService.openSlots(homeroom, (short) 2026, tomorrow,
                LocalTime.of(14, 0), LocalTime.of(15, 0), 20, (short) 1, null);
        em.flush();
        assertThat(first).hasSize(3);

        // 14~16시로 다시 연다. 앞의 3칸은 그대로 두고 뒤만 늘어난다
        List<ConsultSlot> second = consultService.openSlots(homeroom, (short) 2026, tomorrow,
                LocalTime.of(14, 0), LocalTime.of(16, 0), 20, (short) 1, null);
        em.flush();
        assertThat(second).hasSize(3);
        assertThat(consultService.mySlots(homeroom, tomorrow, tomorrow)).hasSize(6);
    }

    @Test
    @DisplayName("★ 남의 일정은 열고 닫을 수 없다 — 그 담임이 모르는 사이에 예약이 끊긴다")
    void cannotPublishOthersSlot() {
        ConsultSlot slot = openOne(otherTeacher, 14);

        assertThatThrownBy(() -> consultService.changePublished(slot.getId(), homeroom, true))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("노출을 내려도 이미 잡힌 예약은 유효하다 — 지우면 학생 기록이 사라진다")
    void unpublishKeepsReservations() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, null);
        em.flush();

        consultService.changePublished(slot.getId(), homeroom, false);
        em.flush();

        assertThat(consultService.myReservations(minji.getId(), tomorrow, tomorrow))
                .singleElement()
                .matches(ConsultReservation::isActive);
    }

    // ── 담임 스코프 ───────────────────────────────────────────

    @Test
    @DisplayName("★ 다른 담임 일정은 예약할 수 없다 — 그 담임이 모르는 학생을 상담하게 된다")
    void cannotReserveOtherTeacherSlot() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(otherTeacher, 14);

        // 목록에는 아예 안 나온다
        assertThat(consultService.availableSlots(minji, tomorrow, tomorrow)).isEmpty();

        // slotId를 직접 넣어도 막힌다
        assertThatThrownBy(() ->
                consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONSULT_NOT_MY_HOMEROOM);
    }

    @Test
    @DisplayName("★ 담임 미배정이면 이유를 알려준다 — 빈 목록이면 '일정이 없나 보다'로 오해한다")
    void noHomeroomIsExplicit() {
        openAndPublish(homeroom, 14);

        assertThatThrownBy(() -> consultService.availableSlots(minji, tomorrow, tomorrow))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONSULT_NO_HOMEROOM);
    }

    // ── 예약 ─────────────────────────────────────────────────

    @Test
    @DisplayName("정원이 차면 거절한다 — 상담 시간은 겹칠 수 없어 대기 개념이 없다")
    void rejectsWhenFull() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, null);
        em.flush();

        Student other = new Student("DL-2026-0500", "박서준", "010-3333-4444");
        em.persist(other);
        StudentEnrollment seojun = new StudentEnrollment(other, bundang, (short) 2026,
                "2026-0002", null, GradeType.HIGH3);
        em.persist(seojun);
        assignHomeroom(seojun, homeroom);

        assertThatThrownBy(() ->
                consultService.reserve(seojun, slot.getId(), ConsultType.SCORE, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONSULT_SLOT_FULL);
    }

    @Test
    @DisplayName("노출되지 않은 일정은 slotId를 알아도 예약되지 않는다")
    void cannotReserveUnpublished() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openOne(homeroom, 14);

        assertThatThrownBy(() ->
                consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONSULT_SLOT_NOT_OPEN);
    }

    @Test
    @DisplayName("★ 취소는 물리 삭제가 아니다 — '몇 번 잡았다 취소했나'가 운영 판단 근거가 된다")
    void cancelKeepsHistory() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        ConsultReservation reservation =
                consultService.reserve(minji, slot.getId(), ConsultType.LIFE, "성적이 떨어져서요");
        em.flush();

        consultService.cancel(reservation.getId(), minji.getId());
        em.flush();

        List<ConsultReservation> mine =
                consultService.myReservations(minji.getId(), tomorrow, tomorrow);
        assertThat(mine).singleElement()
                .matches(r -> !r.isActive())
                .matches(r -> r.getCanceledAt() != null)
                .matches(r -> "성적이 떨어져서요".equals(r.getRequestNote()));

        // 취소했으면 같은 슬롯을 다시 잡을 수 있어야 한다
        assertThat(consultService.reserve(minji, slot.getId(), ConsultType.LIFE, null))
                .isNotNull();
    }

    @Test
    @DisplayName("남의 예약은 취소할 수 없다")
    void cannotCancelOthers() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        ConsultReservation reservation =
                consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, null);
        em.flush();

        assertThatThrownBy(() -> consultService.cancel(reservation.getId(), 999L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("지난 일정은 예약도 취소도 받지 않는다 — 노쇼 여부를 알 수 없게 된다")
    void pastSlotIsClosed() {
        assignHomeroom(minji, homeroom);
        LocalDate yesterday = LocalDate.now(clock).minusDays(1);
        ConsultSlot past = consultService.openSlots(homeroom, (short) 2026, yesterday,
                LocalTime.of(14, 0), LocalTime.of(14, 30), 30, (short) 1, null).get(0);
        consultService.changePublished(past.getId(), homeroom, true);
        em.flush();

        assertThatThrownBy(() ->
                consultService.reserve(minji, past.getId(), ConsultType.REGULAR, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONSULT_SLOT_PAST);
    }

    // ── 알림 ─────────────────────────────────────────────────

    @Test
    @DisplayName("★ 예약하면 담임에게 알림 이력이 남는다 — 문구 미확정이라 실제 발송은 건너뛴다")
    void notifiesHomeroom() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        consultService.reserve(minji, slot.getId(), ConsultType.ADMISSION, null);
        em.flush();

        assertThat(notificationLogRepository.findAll())
                .extracting(l -> l.getEventCode())
                .contains(NotificationEvent.CONSULT_RESERVED);
    }

    @Test
    @DisplayName("취소도 담임에게 알린다 — 없으면 빈 자리를 모른 채 그 시간을 비워둔다")
    void notifiesOnCancel() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        ConsultReservation reservation =
                consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, null);
        em.flush();

        consultService.cancel(reservation.getId(), minji.getId());
        em.flush();

        assertThat(notificationLogRepository.findAll())
                .extracting(l -> l.getEventCode())
                .contains(NotificationEvent.CONSULT_CANCELED);
    }

    // ── 담임 화면 ─────────────────────────────────────────────

    @Test
    @DisplayName("★ 예약자 명단은 담임에게만 보인다 — 학생 화면에 남의 상담이 보이면 안 된다")
    void namesOnlyForTeacher() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, "진학 상담이요");
        em.flush();

        assertThat(consultService.mySlots(homeroom, tomorrow, tomorrow))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.reserved()).isEqualTo(1);
                    assertThat(v.reservations()).singleElement()
                            .matches(r -> "김민지".equals(r.getEnrollment().getStudent().getName()));
                });

        assertThat(consultService.availableSlots(minji, tomorrow, tomorrow))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.reserved()).isEqualTo(1);   // 마감 표시용 인원 수는 준다
                    assertThat(v.reservations()).isEmpty();  // 누구인지는 주지 않는다
                    assertThat(v.isFull()).isTrue();
                });
    }

    @Test
    @DisplayName("일지를 이으면 노쇼(일지 없는 예약)와 구분된다")
    void linksConsultLog() {
        assignHomeroom(minji, homeroom);
        ConsultSlot slot = openAndPublish(homeroom, 14);
        ConsultReservation reservation =
                consultService.reserve(minji, slot.getId(), ConsultType.REGULAR, null);
        em.flush();

        assertThat(reservation.getConsultLogId()).isNull();
        consultService.linkLog(reservation.getId(), 12345L);

        assertThat(reservation.getConsultLogId()).isEqualTo(12345L);
    }
}
