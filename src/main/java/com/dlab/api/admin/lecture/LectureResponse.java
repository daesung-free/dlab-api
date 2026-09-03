package com.dlab.api.admin.lecture;

import com.dlab.domain.lecture.entity.*;
import com.dlab.domain.lecture.service.LectureService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** 특강 응답 DTO. */
public final class LectureResponse {

    private LectureResponse() {
    }

    /**
     * @param visible 앱 노출 여부. {@code status}와 별개 축이다
     * @param fee     ⚠️ 안내용 금액. 결제 연동이 없어 이 값으로 수납되지 않는다
     */
    public record LectureDetail(Long id, String lectureType, String name, String description,
                         String status, boolean visible, Integer capacity,
                         Instant applyFrom, Instant applyTo,
                         LocalDate startDate, LocalDate endDate, int fee,
                         Long teacherId, String teacherName,
                         Long confirmedCount, Long waitlistedCount) {

        public static LectureDetail from(Lecture l) {
            return of(l, null, null);
        }

        public static LectureDetail of(Lecture l, Long confirmed, Long waitlisted) {
            return new LectureDetail(l.getId(), l.getLectureType().name(), l.getName(), l.getDescription(),
                    l.getStatus().name(), l.isVisible(), l.getCapacity(),
                    l.getApplyFrom(), l.getApplyTo(), l.getStartDate(), l.getEndDate(), l.getFee(),
                    l.getTeacher() == null ? null : l.getTeacher().getId(),
                    l.getTeacher() == null ? null : l.getTeacher().getName(),
                    confirmed, waitlisted);
        }

        public static LectureDetail withHeadcount(Lecture l, LectureService.Headcount h) {
            return of(l, h.confirmed(), h.waitlisted());
        }
    }

    public record Session(Long id, short sessionNo, LocalDate sessionDate,
                         LocalTime startTime, LocalTime endTime, String room) {

        public static Session from(LectureSession s) {
            return new Session(s.getId(), s.getSessionNo(), s.getSessionDate(),
                    s.getStartTime(), s.getEndTime(), s.getRoom());
        }
    }

    /**
     * 명단 한 줄.
     *
     * @param waitlisted 대기 여부. 대기 순번은 <b>이 목록의 순서</b>다 —
     *                   순번 컬럼을 두면 앞사람 취소마다 전부 다시 써야 한다
     */
    /**
     * @param className 고정반. 반 미배정이면 비어 있다
     * @param phone     연락처. {@code masked}가 참이면 가려진 값이다
     * @param masked    실제로 가려졌는지. 화면이 또 가리지 않도록 알려준다
     */
    public record RosterRow(Long applicationId, Long studentId, String studentNo,
                            String studentName, String className, String phone,
                            String status, boolean waitlisted,
                            Instant appliedAt, String memo, boolean masked) {

        public static RosterRow from(LectureApplication a) {
            return of(a, null, false);
        }

        public static RosterRow of(com.dlab.domain.lecture.service.LectureService.RosterEntry e,
                                   boolean raw) {
            return of(e.application(), e.className(), raw);
        }

        static RosterRow of(LectureApplication a, String className, boolean raw) {
            String phone = a.getEnrollment().getStudent().getPhone();
            return new RosterRow(a.getId(),
                    a.getEnrollment().getStudent().getId(),
                    a.getEnrollment().getStudentNo(),
                    a.getEnrollment().getStudent().getName(),
                    className,
                    raw ? phone : com.dlab.common.privacy.Masking.phone(phone),
                    a.getStatus().name(),
                    a.getStatus() == ApplicationStatus.WAITLISTED,
                    a.getAppliedAt(), a.getMemo(), !raw);
        }
    }

    public record Attendance(Long id, Long applicationId, String status, String memo) {

        public static Attendance from(LectureAttendance a) {
            return new Attendance(a.getId(), a.getApplication().getId(),
                    a.getStatus().name(), a.getMemo());
        }
    }
}
