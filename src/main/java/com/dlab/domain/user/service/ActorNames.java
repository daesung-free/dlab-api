package com.dlab.domain.user.service;

import com.dlab.common.config.SecurityAuditorAware;
import com.dlab.domain.user.repository.AccountRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정 id → 사람 이름.
 *
 * <p>이력 화면이 「누가 했나」를 표시할 때 쓴다. 계정 유형을 가로질러
 * {@code employee}·{@code teacher}·{@code student} 중 있는 이름을 가져온다.
 *
 * <h2>★ 왜 화면이 따로 맞추지 않고 서버가 함께 내리나</h2>
 * 이력 화면을 <b>지점 관리자도 보는데</b> 그 계정에는 계정 목록 조회 권한이 없을 수 있다.
 * 화면이 id 로 이름을 따로 조회하게 맡기면 <b>그 화면에서는 영원히 「-」가 된다.</b>
 *
 * <h2>★ 한 번에 받는다</h2>
 * 행마다 조회하면 50줄짜리 이력에 쿼리가 50번 나간다.
 *
 * <h2>★ 비어 있는 것이 「사람이 아니다」라는 뜻이다</h2>
 * 배치·스케줄러·DSA 호환 구획은 시스템 계정({@code 0})으로 기록되고 그 id 의 계정은
 * 존재하지 않는다. 그래서 이름이 안 나오고, 화면은 그걸로 「시스템」을 구분한다 —
 * {@code null} 로 두는 것과 「그냥 빠뜨렸다」가 섞이지 않게 애초에 조회에서 제외한다.
 *
 * <h2>⚠️ 당시 이름이 아니라 지금 이름이다</h2>
 * {@code employee}·{@code teacher} 를 그때그때 읽으므로 <b>그 사람이 이름을 바꾸면 과거
 * 이력의 이름도 같이 바뀐다.</b> 그게 의도다 — 이력에 당시 이름을 박아 두면 「김영희가
 * 바꿨다」는 남지만 <b>지금 그 사람이 누군지 찾을 수 없다.</b> 추적은 id 로 하고
 * 이름은 표시용이다.
 */
@Component
@RequiredArgsConstructor
public class ActorNames {

    private final AccountRepository accountRepository;

    /** 그 계정들의 이름. 시스템 계정과 이름 없는 계정은 결과에서 빠진다. */
    @Transactional(readOnly = true)
    public Map<Long, String> of(Collection<Long> accountIds) {
        Set<Long> ids = accountIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> !id.equals(SecurityAuditorAware.SYSTEM_ACCOUNT_ID))
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (Object[] row : accountRepository.findActorNames(ids)) {
            if (row[1] != null) {
                names.put((Long) row[0], (String) row[1]);
            }
        }
        return names;
    }
}
