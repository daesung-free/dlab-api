package com.dlab.domain.notification.service;

import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.StudentGuardianLinkRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「이 학생의 알림을 누가 받는가」 — <b>한 군데</b>.
 *
 * <h2>★★ 앱을 설치하지 않은 보호자에게도 보낸다</h2>
 * 그전까지 모든 경로가 <b>학부모 앱 계정</b>만 찾았다. 보호자 번호가 앱 가입으로만
 * 생겼으니 그때는 맞았는데, 두 가지가 그 전제를 깼다.
 * <ul>
 *   <li>신입생 동의서가 <b>「보호자 전화번호」를 필수로 수집</b>한다 —
 *       수집하는데 알림이 안 가면 수집 이유가 설명되지 않는다</li>
 *   <li>학생 가입·관리자 화면에서 <b>계정 없이 번호만</b> 들어올 수 있게 됐다</li>
 * </ul>
 * ⚠️ 특히 <b>미등원 알림은 끌 수 없는 필수 알림</b>이라, 수신처가 없으면
 * <b>무단결석을 아무도 모르게 된다.</b>
 *
 * <h2>★ 계정이 있으면 계정으로, 없으면 번호로</h2>
 * 계정이 있는 쪽을 먼저 쓴다 — <b>수신거부 설정을 읽을 수 있고</b> 앱 푸시도 같은 계정으로
 * 간다. 번호만 있는 보호자는 설정이 없으니 거부 판정이 성립하지 않는다(필수 알림이라
 * 어차피 거부되지 않는다).
 *
 * <h2>★ 한 보호자에게 두 번 보내지 않는다</h2>
 * 계정이 있는 보호자는 번호로도 걸리므로 <b>보호자 단위로 걸러야</b> 한다 —
 * 안 걸면 가입한 학부모가 같은 알림을 두 통 받는다.
 *
 * <h2>왜 한 군데에 두는가</h2>
 * 미등원 배치 · 출결 알림 · 직원 수동 발송이 각자 조회하면 조건이 갈려
 * <b>「미리보기엔 빠졌는데 실제로는 갔다」</b>가 된다.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GuardianRecipientResolver {

    private final AccountRepository accountRepository;
    private final StudentGuardianLinkRepository linkRepository;

    /**
     * 수신자 한 사람.
     *
     * @param account 앱 계정. <b>번호만 있는 보호자는 {@code null}</b> 이다
     * @param phone   알림톡이 나갈 번호
     */
    public record GuardianRecipient(Account account, ParentGuardian guardian,
                                    String name, String phone) {

        public Long accountId() {
            return account == null ? null : account.getId();
        }

        /** 중복 방지 키에 쓸 식별자. ★ 계정이 없어도 보호자 행은 있다. */
        public Long guardianId() {
            return guardian == null ? null : guardian.getId();
        }

        public boolean hasPhone() {
            return phone != null && !phone.isBlank();
        }
    }

    /**
     * 그 학생의 보호자 수신자 전원.
     *
     * <p>★ 부·모 양쪽에 간다(2026-10-12 회신 — 선착순 승인과 같은 전제).
     * {@code is_approver} 는 보지 않는다 — 그 컬럼은 승인 판정에 쓰이지 않는다.
     */
    public List<GuardianRecipient> of(Long studentId) {
        List<GuardianRecipient> recipients = new ArrayList<>();
        Set<Long> seenGuardians = new LinkedHashSet<>();

        // ① 계정이 있는 보호자 — 수신거부 설정을 읽을 수 있어 먼저 쓴다
        for (Account account : accountRepository.findGuardianAccountsOfStudent(studentId)) {
            ParentGuardian guardian = account.getGuardian();
            if (guardian != null) {
                seenGuardians.add(guardian.getId());
            }
            recipients.add(new GuardianRecipient(account, guardian,
                    guardian == null ? null : guardian.getName(),
                    guardian == null ? null : guardian.getPhone()));
        }

        // ② 계정 없이 번호만 있는 보호자 — ★ 여기가 새로 열린 경로다
        for (var link : linkRepository.findByStudentId(studentId)) {
            ParentGuardian guardian = link.getGuardian();
            if (guardian == null || !seenGuardians.add(guardian.getId())) {
                // 이미 계정으로 잡힌 보호자다 — 두 번 보내면 같은 알림을 두 통 받는다
                continue;
            }
            recipients.add(new GuardianRecipient(null, guardian,
                    guardian.getName(), guardian.getPhone()));
        }
        return recipients;
    }
}
