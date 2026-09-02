package com.dlab.api.admin.clazz;

import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.ClassType;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import com.dlab.domain.user.service.ClassService;

public record ClassResponse(
        Long id,
        Long academyId,
        short year,
        String name,
        ClassType classType,
        Long homeroomTeacherId,
        String homeroomTeacherName
) {

    public static ClassResponse from(ClassMaster c) {
        return new ClassResponse(
                c.getId(),
                c.getAcademy().getId(),
                c.getYear(),
                c.getName(),
                c.getClassType(),
                c.getHomeroomTeacher() == null ? null : c.getHomeroomTeacher().getId(),
                c.getHomeroomTeacher() == null ? null : c.getHomeroomTeacher().getName());
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
