package com.dlab.domain.notification.service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「이 번호로 실제 발송해도 되는가」 — 이관 기간용 안전장치.
 *
 * <h2>★★ 왜 필요한가</h2>
 * 알림톡 테스트 모드를 끈 뒤로 <b>보호자 번호를 넣는 순간 그 학부모에게 실제로 나간다.</b>
 * 지금은 운영의 모든 번호가 미배정 대역({@code 010-0000-*})이라 안전하지만,
 * 컷오버 때 <b>실제 번호를 넣기 시작하면 그 안전이 사라진다</b> —
 * 번호를 다 넣어 두고 검증하는 기간이 필요한데, 그동안 알림이 학부모에게 나가면 안 된다.
 *
 * <h2>★ 테스트 모드와 다르다</h2>
 * 알리고 {@code testmode_yn} 은 <b>아무에게도 안 보낸다</b> — 발송 경로를 검증할 수 없다.
 * 이쪽은 <b>「누구에게」만 가른다</b>. 허용한 번호로는 실제로 나가므로
 * <b>이관 중에도 끝까지 검증할 수 있다.</b>
 *
 * <h2>★ 기본은 전면 개방이다</h2>
 * 비어 있으면 <b>아무도 막지 않는다.</b> 반대로 두면(비어 있을 때 전부 차단) 설정을
 * 빠뜨린 환경에서 <b>알림이 조용히 전멸</b>하고, 그건 알아채기까지 오래 걸린다.
 * ⚠️ 즉 <b>이 장치는 켜야 작동한다</b> — 이관 시작 전에 켜는 것이 체크리스트 항목이다.
 *
 * <h2>⚠️ 끝나면 반드시 비울 것</h2>
 * 켠 채로 두면 <b>그 번호들만 알림을 받는다.</b> 미등원 알림이 전 지점에서 조용히 멈추고,
 * 화면에는 {@code SKIPPED} 로만 남는다.
 */
@Slf4j
@Component
public class RecipientAllowlist {

    private final Set<String> allowed;

    /**
     * @param raw 쉼표로 구분한 번호 목록. 비우면 전면 개방.
     *            ★ 생성자로 받는다 — 불변이고, 테스트가 설정 없이 바로 만들 수 있다
     */
    public RecipientAllowlist(@Value("${notification.allowlist:}") String raw) {
        this.allowed = Arrays.stream(raw == null ? new String[0] : raw.split(","))
                .map(RecipientAllowlist::normalize)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        if (allowed.isEmpty()) {
            log.info("알림 수신 허용목록이 비어 있다 — 전면 개방이다(정상).");
        } else {
            // ★ 번호를 로그에 남기지 않는다. 개수만으로도 켜졌는지는 알 수 있다
            log.warn("★★ 알림 수신 허용목록이 켜져 있다 — {}개 번호에만 실제로 나간다. "
                    + "나머지는 SKIPPED 로 남는다. ⚠️ 이관이 끝나면 비울 것.", allowed.size());
        }
    }

    /**
     * 보내도 되는 번호인가.
     *
     * <p>★ <b>번호가 없으면 통과시킨다</b> — 「허용되지 않았다」가 아니라 「판단할 수 없다」다.
     * 번호 없음은 발송 경로가 따로 걸러낸다(두 곳에서 같은 일을 하면 사유가 헷갈린다).
     */
    public boolean allows(String phone) {
        if (allowed.isEmpty() || phone == null || phone.isBlank()) {
            return true;
        }
        return allowed.contains(normalize(phone));
    }

    /** 하이픈·공백을 떼고 비교한다 — 저장 형식이 섞여 있어도 걸리게. */
    private static String normalize(String phone) {
        return phone == null ? "" : phone.replaceAll("[^0-9]", "");
    }
}
