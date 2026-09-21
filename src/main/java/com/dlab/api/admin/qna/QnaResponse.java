package com.dlab.api.admin.qna;

import com.dlab.domain.qna.entity.QnaOfflineReservation;
import com.dlab.domain.qna.entity.QnaOfflineSlot;
import com.dlab.domain.qna.service.QnaOfflineService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** 질의응답 대면 응답 DTO. */
public final class QnaResponse {

    private QnaResponse() {
    }

    /**
     * 슬롯 한 줄.
     *
     * @param reservations 예약자 명단. <b>앱 응답에서는 비어 있다</b> — 남의 예약이 보이면 안 된다
     * @param full         정원이 찼는가. 앱이 "마감"을 표시한다
     */
    public record QnaSlot(Long id, LocalDate date, LocalTime startTime, LocalTime endTime,
                       String teacherName, String room, int capacity, long reserved,
                       boolean full, boolean closed, String memo,
                       List<QnaReservation> reservations) {

        public static QnaSlot from(QnaOfflineService.SlotView view) {
            return from(view, java.util.Map.of());
        }

        /** @param photos 예약 id → 사진. 관리자 화면이 쓴다 */
        public static QnaSlot from(QnaOfflineService.SlotView view,
                                   java.util.Map<Long, List<QnaOfflineService.Photo>> photos) {
            QnaOfflineSlot s = view.slot();
            return new QnaSlot(s.getId(), s.getSlotDate(), s.getStartTime(), s.getEndTime(),
                    s.getTeacher() == null ? null : s.getTeacher().getName(),
                    s.getRoom(), s.getCapacity(), view.reserved(),
                    view.isFull(), s.isClosed(), s.getMemo(),
                    view.reservations().stream()
                            .map(r -> QnaReservation.of(r, view.classNameOf(r),
                                    photos.getOrDefault(r.getId(), List.of()))).toList());
        }
    }

    /** @param className 고정반. 반 미배정이면 비어 있다 */
    /**
     * @param subject 과목(자유 입력)
     * @param photos  질문 사진. {@code url}은 짧게 유효한 주소라 만료되면 목록을 다시 받는다
     */
    public record QnaReservation(Long id, Long studentId, String studentNo, String studentName,
                              String className, String question,
                              Instant reservedAt, Instant canceledAt,
                              String subject, List<QnaOfflineService.Photo> photos) {

        public static QnaReservation from(QnaOfflineReservation r) {
            return of(r, null, List.of());
        }

        public static QnaReservation of(QnaOfflineReservation r, String className) {
            return of(r, className, List.of());
        }

        public static QnaReservation of(QnaOfflineReservation r, String className,
                                        List<QnaOfflineService.Photo> photos) {
            return new QnaReservation(r.getId(),
                    r.getEnrollment().getStudent().getId(),
                    r.getEnrollment().getStudentNo(),
                    r.getEnrollment().getStudent().getName(),
                    className,
                    r.getQuestion(), r.getReservedAt(), r.getCanceledAt(),
                    r.getSubject(), photos);
        }
    }

    /** 학생 본인 예약 내역. 취소분도 이력으로 나온다({@code canceledAt}). */
    public record MyReservation(Long id, LocalDate date, LocalTime startTime, LocalTime endTime,
                                String teacherName, String room, String question,
                                Instant reservedAt, Instant canceledAt,
                                String subject, List<QnaOfflineService.Photo> photos) {

        public static MyReservation from(QnaOfflineReservation r) {
            return from(r, List.of());
        }

        public static MyReservation from(QnaOfflineReservation r,
                                         List<QnaOfflineService.Photo> photos) {
            QnaOfflineSlot s = r.getSlot();
            return new MyReservation(r.getId(), s.getSlotDate(), s.getStartTime(), s.getEndTime(),
                    s.getTeacher() == null ? null : s.getTeacher().getName(),
                    s.getRoom(), r.getQuestion(), r.getReservedAt(), r.getCanceledAt(),
                    r.getSubject(), photos);
        }
    }
}
