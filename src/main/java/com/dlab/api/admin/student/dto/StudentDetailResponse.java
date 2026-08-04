package com.dlab.api.admin.student.dto;

import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.TrackType;
import java.time.LocalDate;
import java.util.List;

/**
 * 학생 상세 (F-4.1).
 *
 * <p>목록({@link StudentSummaryResponse})과 달리 <b>생년월일·보호자 연락처가 들어간다.</b>
 * 그래서 이 응답은 열람 권한에 따라 값이 달라진다 — 상위 관리자가 아니면 마스킹된 값이
 * 채워지고, {@code masked=true}로 그 사실을 알린다. 화면이 마스킹 여부를 모르면
 * "전화번호가 잘못 저장됐다"는 오인 문의가 생긴다.
 *
 * @param uniqueCode 학생 고유ID. 학부모가 가입 시 입력하는 값이라 마이페이지에도 노출된다.
 *                   학번과 다르다 — 학번은 매년 초기화되지만 이건 사람에 고정이다
 * @param masked     민감 필드가 마스킹됐는지
 */
public record StudentDetailResponse(
        Long enrollmentId,
        Long studentId,
        String uniqueCode,
        String studentNo,
        String name,
        Integer year,
        GradeType grade,
        TrackType track,
        EnrollmentStatus status,
        String phone,
        String birthDate,
        String gender,
        String schoolName,
        LocalDate admissionDate,
        LocalDate withdrawalDate,
        boolean rfidIssued,
        boolean current,
        Long academyId,
        String academyName,
        ClassInfo classInfo,
        List<GuardianInfo> guardians,
        boolean masked
) {

    /** 배정된 반과 담임. <b>담임이 곧 방화벽 에스컬레이션 승인자다</b>(CLAUDE.md §3). */
    public record ClassInfo(
            Long classId,
            String className,
            Long homeroomTeacherId,
            String homeroomTeacherName
    ) {
    }

    /** 보호자. 0803 시트 기준 최대 1인이지만 스키마가 다건을 허용해 목록으로 둔다. */
    public record GuardianInfo(
            Long guardianId,
            String name,
            String phone,
            String gender,
            short relationOrder
    ) {
    }
}
