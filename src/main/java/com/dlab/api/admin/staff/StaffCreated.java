package com.dlab.api.admin.staff;

import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.AccountStatus;

import java.util.Set;

/**
 * 직원·선생님 등록 결과 (F-4.10-2).
 *
 * <p><b>사람 정보만 돌려주면 화면이 방금 만든 행을 그릴 수 없다.</b> 계정이 분명히
 * 만들어졌는데 {@code accountId}·{@code loginId}가 비어서, 프론트가 저장 직후
 * 목록을 다시 부르고 있었다.
 *
 * @param temporaryPassword <b>여기서 딱 한 번만 나간다.</b> 서버가 저장하지 않으므로
 *                          놓치면 재발급해야 한다 — 보관하면 그 자체가 유출 경로다.
 *                          받는 사람은 <b>첫 로그인에서 반드시 변경</b>하게 된다.
 * @param pendingApproval   {@code true}면 본사 승인 전까지 로그인이 막힌다.
 *                          지점이 만든 계정이 여기 해당한다 — 화면이 이걸 안 알리면
 *                          "계정 만들어 줬는데 왜 로그인이 안 되냐"를 듣게 된다.
 */
public record StaffCreated(
        StaffResponse staff,
        Long accountId,
        String loginId,
        Set<String> roles,
        String status,
        String temporaryPassword,
        boolean pendingApproval
) {

    public static StaffCreated of(StaffResponse staff, Account account, Set<String> roles,
                                  String temporaryPassword) {
        return new StaffCreated(staff, account.getId(), account.getLoginId(), roles,
                account.getStatus().name(), temporaryPassword,
                account.getStatus() == AccountStatus.PENDING);
    }
}
