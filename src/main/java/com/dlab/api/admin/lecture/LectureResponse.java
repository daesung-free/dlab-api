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
    public record LectureDetail(Long id, String lectureType, String name, String code,
                         String description,
                         String status, boolean visible, Integer capacity,
                         Instant applyFrom, Instant applyTo,
                         LocalDate startDate, LocalDate endDate, int fee,
                         Long teacherId, String teacherName,
                         Long categoryId, String categoryName,
                         Long confirmedCount, Long waitlistedCount,
                         // ★ 대상이 비어 있으면 「제한 없음」이다 — 화면이 「전체」로 표시하면 된다.
                         //   「전체」를 값으로 되돌려 보내지 말 것(저장이 두 가지로 갈린다)
                         java.util.Set<String> targetTracks,
                         java.util.Set<String> targetGrades,
                         Integer waitlistCapacity) {

        public static LectureDetail from(Lecture l) {
            return of(l, null, null);
        }

        public static LectureDetail of(Lecture l, Long confirmed, Long waitlisted) {
            return new LectureDetail(l.getId(), l.getLectureType().name(), l.getName(),
                    l.getCode(), l.getDescription(),
                    l.getStatus().name(), l.isVisible(), l.getCapacity(),
                    l.getApplyFrom(), l.getApplyTo(), l.getStartDate(), l.getEndDate(), l.getFee(),
                    l.getTeacher() == null ? null : l.getTeacher().getId(),
                    l.getTeacher() == null ? null : l.getTeacher().getName(),
                    l.getCategory() == null ? null : l.getCategory().getId(),
                    l.getCategory() == null ? null : l.getCategory().getName(),
                    confirmed, waitlisted,
                    l.getTargetTracks().stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.toCollection(
                                    java.util.LinkedHashSet::new)),
                    l.getTargetGrades().stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.toCollection(
                                    java.util.LinkedHashSet::new)),
                    l.getWaitlistCapacity());
        }

        public static LectureDetail withHeadcount(Lecture l, LectureService.Headcount h) {
            return of(l, h.confirmed(), h.waitlisted());
        }
    }

    /**
     * @param fee 그 회차 수강료. ★ {@code null} 은 <b>미설정</b>이다 —
     *            화면에서 0원으로 표시하지 말 것. 「0원 무료」와 구분되지 않는다
     */
    public record Session(Long id, short sessionNo, LocalDate sessionDate,
                         LocalTime startTime, LocalTime endTime, String room,
                         Integer fee) {

        public static Session from(LectureSession s) {
            return new Session(s.getId(), s.getSessionNo(), s.getSessionDate(),
                    s.getStartTime(), s.getEndTime(), s.getRoom(), s.getFee());
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
    /**
     * @param studentId    사람(student) id
     * @param enrollmentId 등록 건 id — 학생 상세·출결 등 다른 관리자 화면으로 넘어갈 때 쓴다
     */
    /**
     * @param grade  학년. 수강 대상 구분(N수생/고3/고2/고1)과 같은 축이다
     * @param seatNo 독서실 좌석번호. ⚠️ <b>배정 전이면 {@code null}</b> 이다 —
     *               화면에 <b>「미배정」으로 쓰지 말 것</b>. <b>좌석을 쓰지 않는 특강</b>도
     *               있어서, 비어 있는 것이 곧 문제라는 뜻이 아니다
     */
    public record RosterRow(Long applicationId, Long studentId, Long enrollmentId, String studentNo,
                            String studentName, String className, String phone,
                            com.dlab.domain.user.entity.GradeType grade, String seatNo,
                            String status, boolean waitlisted,
                            Instant appliedAt, String memo, boolean masked) {

        public static RosterRow from(LectureApplication a) {
            return of(a, null, null, false);
        }

        public static RosterRow of(com.dlab.domain.lecture.service.LectureService.RosterEntry e,
                                   boolean raw) {
            return of(e.application(), e.className(), e.seatNo(), raw);
        }

        static RosterRow of(LectureApplication a, String className, String seatNo, boolean raw) {
            String phone = a.getEnrollment().getStudent().getPhone();
            return new RosterRow(a.getId(),
                    a.getEnrollment().getStudent().getId(),
                    a.getEnrollment().getId(),
                    a.getEnrollment().getStudentNo(),
                    a.getEnrollment().getStudent().getName(),
                    className,
                    raw ? phone : com.dlab.common.privacy.Masking.phone(phone),
                    a.getEnrollment().getGrade(), seatNo,
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
