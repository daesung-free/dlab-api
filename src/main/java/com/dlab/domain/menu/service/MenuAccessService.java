package com.dlab.domain.menu.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.menu.entity.AccountMenu;
import com.dlab.domain.menu.entity.Menu;
import com.dlab.domain.menu.repository.AccountMenuRepository;
import com.dlab.domain.menu.repository.MenuRepository;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.repository.AccountRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정별 메뉴 노출 (0914 확정).
 *
 * <h2>★ 역할이 아니라 계정 단위다</h2>
 * "조회 전용 계정 하나하나에 대해 최고관리자가 보여질 메뉴를 고른다" 가 확정 사항이다.
 * 역할로 일괄 제한하면 같은 {@code READONLY} 끼리 다르게 줄 수가 없다.
 *
 * <h2>★★ 좁히기만 한다 — 권한을 늘리지 않는다</h2>
 * 메뉴를 준다고 역할에 없는 권한이 생기지 않는다. 역할 검사({@code @PreAuthorize})는 그대로
 * 앞에 있고, 이 설정은 그 위에 한 겹 더 좁힐 뿐이다. 반대로 만들면 <b>메뉴 한 칸 체크가
 * 권한 상승이 된다.</b>
 *
 * <h2>설정이 없으면 제한 없음이다</h2>
 * 행이 하나도 없는 계정은 역할 권한 그대로 쓴다. "아무것도 못 봄" 으로 해석하면
 * <b>설정을 만들지 않은 기존 계정이 전부 잠긴다.</b>
 */
@Service
@RequiredArgsConstructor
public class MenuAccessService {

    /** 자주 쓰는 메뉴 상한. 대시보드 한 칸에 들어가야 한다 */
    private static final int FAVORITE_LIMIT = 8;

    private final MenuRepository menuRepository;
    private final AccountMenuRepository accountMenuRepository;
    private final com.dlab.domain.menu.repository.FavoriteMenuRepository favoriteMenuRepository;
    private final AccountRepository accountRepository;

    @Transactional(readOnly = true)
    public List<Menu> catalog() {
        return menuRepository.findAllOrdered();
    }

    /** 이 계정에 지정된 메뉴. 비어 있으면 제한이 걸려 있지 않은 것이다. */
    @Transactional(readOnly = true)
    public List<Menu> allowedMenus(Long accountId) {
        return accountMenuRepository.findByAccountId(accountId).stream()
                .map(AccountMenu::getMenu)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean restricted(Long accountId) {
        return !accountMenuRepository.findByAccountId(accountId).isEmpty();
    }

    /**
     * 이 계정에 보여줄 메뉴.
     *
     * <p>제한이 없으면 카탈로그 전체를 돌려준다 — 화면이 "설정 없음" 을 따로 처리하지
     * 않아도 되게 한다.
     */
    @Transactional(readOnly = true)
    public List<Menu> visibleMenus(Long accountId) {
        List<Menu> allowed = allowedMenus(accountId);
        return allowed.isEmpty() ? catalog() : allowed;
    }

    /**
     * 설정을 통째로 바꾼다.
     *
     * <p><b>빈 목록은 "제한 해제"</b>다. 지정한 메뉴만 남기고 나머지는 지운다 — 더하기·빼기로
     * 두면 화면이 현재 상태를 정확히 모를 때 의도하지 않은 조합이 남는다.
     */
    @Transactional
    public List<Menu> replace(AuthPrincipal me, Long accountId, List<String> menuCodes) {
        if (!me.roles().contains(Role.SUPER_ADMIN)) {
            // 지점 관리자가 남의 노출 범위를 바꾸면 사실상 권한 관리가 지점으로 내려간다
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "메뉴 노출 설정은 최고관리자만 바꿀 수 있습니다.");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        accountMenuRepository.deleteByAccountId(accountId);
        accountMenuRepository.flush();

        if (menuCodes == null || menuCodes.isEmpty()) {
            return List.of();
        }
        List<Menu> menus = menuRepository.findByCodes(menuCodes);
        if (menus.size() != menuCodes.stream().distinct().count()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "없는 메뉴 코드가 있습니다. 메뉴 목록을 다시 받아 주세요.");
        }
        menus.forEach(menu -> accountMenuRepository.save(new AccountMenu(account, menu)));
        return menus;
    }

    /** 내 자주 쓰는 메뉴. 정한 순서 그대로다. */
    @Transactional(readOnly = true)
    public List<Menu> favorites(Long accountId) {
        return favoriteMenuRepository.findByAccountId(accountId).stream()
                .map(com.dlab.domain.menu.entity.FavoriteMenu::getMenu)
                .toList();
    }

    /**
     * 자주 쓰는 메뉴를 바꾼다. 보낸 순서가 그대로 화면 순서다.
     *
     * <p>★ <b>볼 수 없는 메뉴는 담기지 않는다.</b> 담아 두면 눌렀을 때 403 이 나는 칸이
     * 대시보드에 남는다 — 편의 설정이 권한을 넓히지도, 깨진 링크를 만들지도 않아야 한다.
     */
    @Transactional
    public List<Menu> replaceFavorites(Long accountId, List<String> menuCodes) {
        favoriteMenuRepository.deleteByAccountId(accountId);
        favoriteMenuRepository.flush();

        if (menuCodes == null || menuCodes.isEmpty()) {
            return List.of();
        }
        if (menuCodes.size() > FAVORITE_LIMIT) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "자주 쓰는 메뉴는 %d개까지입니다.".formatted(FAVORITE_LIMIT));
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        List<Menu> visible = visibleMenus(accountId);
        List<Menu> picked = new java.util.ArrayList<>();
        short order = 0;
        for (String code : menuCodes.stream().distinct().toList()) {
            Menu menu = visible.stream()
                    .filter(m -> m.getCode().equals(code))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                            "담을 수 없는 메뉴입니다: %s".formatted(code)));
            favoriteMenuRepository.save(
                    new com.dlab.domain.menu.entity.FavoriteMenu(account, menu, order++));
            picked.add(menu);
        }
        return picked;
    }

    /**
     * 이 요청을 허용할지.
     *
     * <p>메뉴 접두사에 걸리지 않는 경로는 <b>허용</b>한다. 로그인·내 정보처럼 어느 메뉴에도
     * 속하지 않는 공통 호출이 있고, 그걸 막으면 화면이 아예 안 뜬다. 대신 그런 경로는
     * 애초에 역할 검사가 지키고 있다.
     */
    @Transactional(readOnly = true)
    public boolean allows(Long accountId, String requestUri) {
        List<Menu> allowed = allowedMenus(accountId);
        if (allowed.isEmpty()) {
            return true;
        }
        Menu owner = menuRepository.findAllOrdered().stream()
                .filter(m -> m.covers(requestUri))
                // 접두사가 겹치면(/tuition 과 /tuition/billings) 더 긴 쪽이 주인이다
                .max(java.util.Comparator.comparingInt(m -> m.getPathPrefix().length()))
                .orElse(null);
        if (owner == null) {
            return true;
        }
        return allowed.stream().anyMatch(m -> m.getId().equals(owner.getId()));
    }
}
