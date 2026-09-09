package com.dlab.api.admin.clazz;

import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import com.dlab.domain.user.service.ClassService;

/**
 * 반 한 줄.
 *
 * @param capacity    정원. <b>{@code null}이면 정원을 두지 않는 반</b>이다 — 0이 아니다.
 *                    화면은 정원이 없으면 충원율을 그리지 않는다
 * @param memberCount 현재 인원. <b>목록에서만 채운다.</b> 단건 응답(생성·수정·담임지정)은
 *                    {@code null}이다 — 세지 않았다는 뜻이라 0과 구분해야 한다
 */
public record ClassResponse(
        Long id,
        Long academyId,
        short year,
        String name,
        ClassType classType,
        Long homeroomTeacherId,
        String homeroomTeacherName,
        Short capacity,
        Integer memberCount
) {

    /** 단건 응답 — 인원수는 세지 않는다. */
    public static ClassResponse from(ClassMaster c) {
        return of(c, null);
    }

    /** 목록 한 줄 — 집계된 인원수를 함께 싣는다. */
    public static ClassResponse from(ClassService.ClassSummaryView view) {
        return of(view.classMaster(), view.memberCount());
    }

    private static ClassResponse of(ClassMaster c, Integer memberCount) {
        return new ClassResponse(
                c.getId(),
                c.getAcademy().getId(),
                c.getYear(),
                c.getName(),
                c.getClassType(),
                c.getHomeroomTeacher() == null ? null : c.getHomeroomTeacher().getId(),
                c.getHomeroomTeacher() == null ? null : c.getHomeroomTeacher().getName(),
                c.getCapacity(),
                memberCount);
    }

    /**
     * 일괄 배정 결과.
     *
     * <p><b>배정 후의 인원·정원을 함께 준다</b> — 화면이 목록을 다시 부르지 않아도
     * 충원율을 갱신할 수 있다.
     *
     * @param overCapacity 정원을 넘겼는지. <b>넘겨도 배정은 된다</b>(정원 초과가 필요한 운영이
     *                     실제로 있다) — 화면이 경고만 띄우면 된다
     */
    public record BulkAssign(
            Long classId,
            Short capacity,
            int memberCount,
            boolean overCapacity,
            int assignedCount,
            int failedCount,
            java.util.List<BulkAssignItem> results
    ) {

        public static BulkAssign from(ClassService.BulkAssignOutcome outcome) {
            java.util.List<BulkAssignItem> items = outcome.results().stream()
                    .map(BulkAssignItem::from).toList();
            return new BulkAssign(
                    outcome.classMaster().getId(),
                    outcome.classMaster().getCapacity(),
                    outcome.memberCount(),
                    outcome.overCapacity(),
                    (int) items.stream().filter(i -> "ASSIGNED".equals(i.status())).count(),
                    (int) items.stream().filter(i -> "FAILED".equals(i.status())).count(),
                    items);
        }
    }

    /**
     * 일괄 배정 건별 결과.
     *
     * @param status {@code ASSIGNED} / {@code DUPLICATE}(같은 요청에 두 번) / {@code FAILED}
     */
    public record BulkAssignItem(Long enrollmentId, String studentName,
                                 String status, String message) {

        static BulkAssignItem from(ClassService.BulkAssignResult r) {
            return new BulkAssignItem(r.enrollmentId(), r.studentName(),
                    r.status().name(), r.message());
        }
    }

    /**
     * 반 소속 학생 한 줄 (F-4.1-4 반 배정 · F-4.10-3 배정 관리).
     *
     * <p><b>재수 차수(재수/삼수/N수)는 없다.</b> {@link GradeType}이 {@code HIGH2/HIGH3/N_SU}라
     * N수 안의 차수를 구분하지 않는다 — 스키마·정책이 정해지기 전에 추측해서 내리면
     * 화면이 틀린 값을 보여준다.
     *
     * <p>좌석은 미배정이면 {@code null}이다. 값이 없는 것과 "좌석 없음"이 같은 뜻이라
     * 빈 문자열로 채우지 않는다.
     */
    public record Member(
            Long enrollmentId,
            String studentNo,
            String studentName,
            GradeType grade,
            TrackType track,
            String schoolName,
            String seatCd,
            Long academyId,
            String academyName
    ) {

        public static Member from(ClassService.ClassMemberView view) {
            StudentEnrollment e = view.assignment().getEnrollment();
            return new Member(
                    e.getId(),
                    e.getStudentNo(),
                    e.getStudentName(),
                    e.getGrade(),
                    e.getTrack(),
                    e.getStudent().getSchoolName(),
                    view.seatCd(),
                    e.getAcademy().getId(),
                    view.academyName());
        }
    }
}
