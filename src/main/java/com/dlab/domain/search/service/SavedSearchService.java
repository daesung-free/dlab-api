package com.dlab.domain.search.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.search.entity.SavedSearch;
import com.dlab.domain.search.entity.SearchType;
import com.dlab.domain.search.repository.SavedSearchRepository;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 검색조건 저장·불러오기 (실행가이드 P1-01 "조건저장").
 *
 * <p><b>개인 설정이라 남의 것은 보이지도, 지워지지도 않는다.</b> 지점 권한과 별개로
 * <b>본인 계정 것만</b> 다룬다 — 같은 지점 관리자라도 남의 저장 조건을 건드릴 이유가 없다.
 */
@Service
@RequiredArgsConstructor
public class SavedSearchService {

    /**
     * 조건 JSON 길이 상한. 서버가 파싱하지 않는 값이라 무제한이면 임의 데이터를 넣는
     * 저장소가 되어버린다. 12개 조건이면 수백 바이트라 넉넉한 값이다.
     */
    private static final int MAX_CONDITION_LENGTH = 4_000;

    private final SavedSearchRepository savedSearchRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<SavedSearch> list(SearchType searchType, AuthPrincipal principal) {
        return savedSearchRepository.findByAccountIdAndSearchTypeAndDeletedFalseOrderByNameAsc(
                principal.accountId(), searchType);
    }

    /**
     * 저장. <b>같은 이름이 있으면 덮어쓴다</b> — 이름이 같은데 조건이 다른 항목이 둘이면
     * 사용자가 어느 게 최신인지 알 수 없다. "저장"을 다시 누른 건 갱신 의도로 본다.
     */
    @Transactional
    public SavedSearch save(SearchType searchType, String name, String conditions,
                            AuthPrincipal principal) {
        if (conditions == null || conditions.length() > MAX_CONDITION_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "검색조건이 비었거나 너무 깁니다.");
        }
        Account account = accountRepository.findById(principal.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        return savedSearchRepository
                .findByAccountIdAndSearchTypeAndNameAndDeletedFalse(
                        principal.accountId(), searchType, name)
                .map(existing -> {
                    existing.update(null, conditions);
                    return existing;
                })
                .orElseGet(() -> savedSearchRepository.save(new SavedSearch(
                        academyOf(account), account, searchType, name, conditions)));
    }

    @Transactional
    public void delete(Long id, AuthPrincipal principal) {
        SavedSearch saved = savedSearchRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.MASTER_NOT_FOUND));
        // 지점이 아니라 소유자로 막는다 — 같은 지점이어도 남의 개인 설정이다
        if (!saved.ownedBy(principal.accountId())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인이 저장한 조건만 삭제할 수 있습니다.");
        }
        saved.markDeleted();
    }

    /**
     * 계정의 소속 지점.
     *
     * <p>학생 계정은 지점이 등록 건에 붙어 있어 여기서 못 꺼내는데, 검색조건 저장은
     * 관리자 화면 기능이라 선생님·직원 계정만 들어온다.
     */
    private com.dlab.domain.user.entity.Academy academyOf(Account account) {
        if (account.getTeacher() != null) {
            return account.getTeacher().getAcademy();
        }
        if (account.getEmployee() != null) {
            return account.getEmployee().getAcademy();
        }
        throw new BusinessException(ErrorCode.FORBIDDEN, "관리자 계정만 검색조건을 저장할 수 있습니다.");
    }
}
