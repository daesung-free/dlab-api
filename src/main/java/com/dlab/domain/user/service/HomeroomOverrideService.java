package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.TeacherRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담임 예외 지정 (0921 연구소 답변).
 *
 * <p>연구소가 *"반은 그대로이나 담임 선생님 변경을 요청하는 경우가 있다"* 로 뒤집었다.
 * <b>반 전체 담임 교체</b>는 이미 {@code PUT /admin/classes/{id}/homeroom} 로 된다 — 여기는
 * <b>같은 반의 일부 학생만</b> 다른 선생님에게 맡기는 경우다.
 *
 * <h2>지정·해제는 최고관리자·지점관리자만</h2>
 * 승인 이양·상담 담당이 따라 움직이는 값이라, 담임이 스스로 학생을 넘기거나 가져오면
 * 책임 소재가 흐려진다.
 *
 * <h2>사유는 필수다</h2>
 * 권한이 따라 움직이는 값이라 "누가 왜 바꿨나" 가 없으면 나중에 되짚지 못한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomeroomOverrideService {

    private final StudentEnrollmentRepository enrollmentRepository;
    private final TeacherRepository teacherRepository;
    private final Clock clock;

    @Transactional
    public StudentEnrollment override(AuthPrincipal me, Long enrollmentId, Long teacherId,
                                      String reason) {
        requireManager(me);
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지정 사유를 입력해 주세요.");
        }
        StudentEnrollment enrollment = require(me, enrollmentId);
        Teacher teacher = teacherRepository.findById(teacherId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "선생님을 찾을 수 없습니다."));
        // 겸직이 없다 — 다른 지점 선생님에게 맡기면 그 선생님은 이 학생을 볼 권한이 없다
        if (!teacher.getAcademy().getId().equals(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "다른 지점 선생님에게는 맡길 수 없습니다.");
        }
        enrollment.overrideHomeroom(teacher, reason.trim(), Instant.now(clock));
        log.info("담임 예외 지정: enrollmentId={}, teacherId={}, 사유={}", enrollmentId, teacherId, reason);
        return enrollment;
    }

    @Transactional
    public StudentEnrollment clear(AuthPrincipal me, Long enrollmentId) {
        requireManager(me);
        StudentEnrollment enrollment = require(me, enrollmentId);
        if (enrollment.getHomeroomOverride() != null) {
            log.info("담임 예외 해제: enrollmentId={}, 해제된 teacherId={}", enrollmentId,
                    enrollment.getHomeroomOverride().getId());
        }
        enrollment.clearHomeroomOverride();
        return enrollment;
    }

    private StudentEnrollment require(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        me.requireAcademyScope(enrollment.getAcademy().getId());
        return enrollment;
    }

    private void requireManager(AuthPrincipal me) {
        if (!me.hasRole(Role.SUPER_ADMIN) && !me.hasRole(Role.BRANCH_ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "담임 예외 지정은 최고관리자·지점관리자만 할 수 있습니다.");
        }
    }
}
