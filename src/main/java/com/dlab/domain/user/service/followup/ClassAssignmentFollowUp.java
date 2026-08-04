package com.dlab.domain.user.service.followup;

import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.service.EnrollmentStatusFollowUp;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 종료 시 반 배정 해제.
 *
 * <p><b>휴원에는 적용하지 않는다.</b> 복귀 예정인데 반을 풀면 돌아왔을 때 자리가 없고,
 * 담임이 사라져 방화벽 승인 에스컬레이션 대상도 없어진다(CLAUDE.md §3).
 *
 * <p>복귀 시 자동 재배정도 하지 않는다 — 그 사이 반 구성이 바뀌었을 수 있어
 * 어느 반으로 돌릴지는 관리자가 정할 문제다.
 */
@Component
@RequiredArgsConstructor
public class ClassAssignmentFollowUp implements EnrollmentStatusFollowUp {

    private final ClassAssignmentRepository classAssignmentRepository;

    @Override
    public void apply(Change change) {
        if (!change.isTerminating()) {
            return;
        }
        classAssignmentRepository.findActiveFixedByEnrollmentId(change.enrollment().getId())
                .ifPresent(assignment -> assignment.deactivate());
    }
}
