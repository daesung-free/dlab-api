package com.dlab.api.admin.student.dto;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import java.time.LocalDate;

/**
 * 학생 목록 행.
 *
 * <p><b>{@code enrollmentId}가 식별자다.</b> 학생(사람) id가 아니다 —
 * 삼수 재등록이면 같은 사람에 등록 건이 여러 개라, 사람 id로는 어느 해 건인지 알 수 없다.
 *
 * <p><b>생년월일·주소는 목록에 넣지 않는다.</b> 민감정보라 상세 조회에서만 노출한다.
 */
public record StudentSummaryResponse(
        Long enrollmentId,
        Long studentId,
        String studentNo,
        String name,
        Integer year,
        GradeType grade,
        TrackType track,
        EnrollmentStatus status,
        String phone,
        String schoolName,
        LocalDate admissionDate,
        boolean rfidIssued
) {

    public static StudentSummaryResponse from(StudentEnrollment e) {
        return new StudentSummaryResponse(
                e.getId(),
                e.getStudent().getId(),
                e.getStudentNo(),
                e.getStudent().getName(),
                (int) e.getYear(),
                e.getGrade(),
                e.getTrack(),
                e.getEnrollmentStatus(),
                e.getStudent().getPhone(),
                e.getStudent().getSchoolName(),
                e.getAdmissionDate(),
                // 카드번호 자체는 내리지 않는다 — 출결 태깅 키라 노출 범위를 좁힌다
                e.getRfidNo() != null);
    }
}
