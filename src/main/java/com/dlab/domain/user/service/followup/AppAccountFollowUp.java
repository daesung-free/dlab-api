package com.dlab.domain.user.service.followup;

import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.service.EnrollmentStatusFollowUp;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 앱 계정 상태 연동.
 *
 * <ul>
 *   <li>퇴원·제적·수료 → {@code WITHDRAWN}</li>
 *   <li>휴원 → {@code SUSPENDED} (복귀 전제라 되돌릴 수 있게)</li>
 *   <li>재원 복귀 → {@code ACTIVE}</li>
 * </ul>
 *
 * <p><b>학부모 계정은 건드리지 않는다.</b> 학부모는 자녀 여러 명을 한 계정에 묶으므로
 * (CLAUDE.md §3) 한 자녀가 퇴원했다고 계정을 죽이면 다른 자녀 알림까지 끊긴다.
 */
@Component
@RequiredArgsConstructor
public class AppAccountFollowUp implements EnrollmentStatusFollowUp {

    private final AccountRepository accountRepository;

    @Override
    public void apply(Change change) {
        Optional<Account> account = accountRepository
                .findByStudentId(change.enrollment().getStudent().getId());
        if (account.isEmpty()) {
            // 아직 앱 가입을 안 한 학생. 관리자가 먼저 등록하고 학생이 나중에 가입하는
            // 순서가 정상이라 계정이 없는 게 오류는 아니다.
            return;
        }

        Account target = account.get();
        if (change.isTerminating()) {
            target.withdraw();
        } else if (change.to() == EnrollmentStatus.ON_LEAVE) {
            target.suspend();
        } else if (change.isReturningToActive()) {
            target.reactivate();
        }
    }
}
