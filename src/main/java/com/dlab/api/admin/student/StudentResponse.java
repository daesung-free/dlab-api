package com.dlab.api.admin.student;

import com.dlab.domain.user.entity.*;

import java.time.LocalDate;

/**
 * 학생 응답.
 *
 * <p>{@code studentId}(사람)와 {@code enrollmentId}(등록 건)를 <b>둘 다 내려준다</b> —
 * 상담·신상기록부는 사람 단위, 출결·청구는 등록 건 단위라 클라이언트가 둘을 구분해야 한다.
 */
public record StudentResponse(
        Long enrollmentId,
        Long studentId,
        String uniqueCode,
        String studentNo,
        String name,
        String phone,
        short year,
        GradeType grade,
        TrackType track,
        EnrollmentStatus enrollmentStatus,
        LocalDate admissionDate
) {

    public static StudentResponse from(StudentEnrollment e) {
        Student s = e.getStudent();
        return new StudentResponse(
                e.getId(), s.getId(), s.getUniqueCode(), e.getStudentNo(),
                s.getName(), s.getPhone(), e.getYear(),
                e.getGrade(), e.getTrack(), e.getEnrollmentStatus(), e.getAdmissionDate());
    }
}
