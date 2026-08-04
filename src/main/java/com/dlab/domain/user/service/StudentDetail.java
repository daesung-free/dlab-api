package com.dlab.domain.user.service;

import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.StudentGuardianLink;
import java.util.List;

/**
 * 학생 상세 조회 결과 묶음.
 *
 * <p>세 곳(등록 건 · 반 배정 · 보호자 연결)에서 모은 것을 한 번에 넘긴다.
 * 화면이 조각마다 API를 부르면 상세 진입에 3왕복이 든다.
 *
 * @param classAssignment 미배정이면 {@code null}. 신규 접수 직후가 이 상태다
 */
public record StudentDetail(
        StudentEnrollment enrollment,
        ClassAssignment classAssignment,
        List<StudentGuardianLink> guardianLinks
) {
}
