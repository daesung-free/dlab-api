package com.dlab.api.admin.menu;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.menu.entity.Menu;
import com.dlab.domain.menu.service.MenuAccessService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 계정별 메뉴 노출 설정 (0914 확정).
 *
 * <h2>★ 역할이 아니라 계정 단위다</h2>
 * 같은 조회 전용 계정이라도 보여줄 메뉴가 다를 수 있어, 최고관리자가 <b>계정 하나하나에</b>
 * 지정한다.
 *
 * <h2>★★ 서버도 같은 설정으로 막는다</h2>
 * 화면에서 감추는 것만으로는 주소를 직접 친 요청을 막지 못한다. 다만 <b>좁히기만 한다</b> —
 * 메뉴를 준다고 역할에 없는 권한이 생기지는 않는다.
 */
@Tag(name = "관리자 · 메뉴 노출 설정")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminMenuController {

    private final MenuAccessService menuAccessService;

    /** 지정할 수 있는 메뉴 전체. 설정 화면이 이 목록으로 체크박스를 그린다. */
    @GetMapping("/menus")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
    public ApiResponse<List<MenuView>> catalog() {
        return ApiResponse.success(menuAccessService.catalog().stream().map(MenuView::from).toList());
    }

    /**
     * 내가 볼 메뉴. 로그인 후 화면이 이걸로 좌측 메뉴를 그린다.
     *
     * <p>설정이 없는 계정에는 <b>전체</b>가 내려온다 — 화면이 "설정 없음" 을 따로 다루지
     * 않아도 되게 한다.
     */
    @GetMapping("/menus/mine")
    public ApiResponse<List<MenuView>> mine(@CurrentAccount AuthPrincipal me) {
        return ApiResponse.success(menuAccessService.visibleMenus(me.accountId())
                .stream().map(MenuView::from).toList());
    }

    /** 이 계정에 지정된 메뉴. {@code restricted=false} 면 제한이 걸려 있지 않은 것이다. */
    @GetMapping("/staff/accounts/{accountId}/menus")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AccountMenuView> ofAccount(@PathVariable Long accountId) {
        List<Menu> allowed = menuAccessService.allowedMenus(accountId);
        return ApiResponse.success(new AccountMenuView(accountId, !allowed.isEmpty(),
                allowed.stream().map(MenuView::from).toList()));
    }

    /**
     * 설정을 통째로 바꾼다.
     *
     * <p><b>빈 목록을 보내면 제한이 풀린다</b>(역할 권한 그대로). 더하기·빼기가 아니라
     * 교체다 — 화면이 현재 상태를 정확히 모를 때 의도하지 않은 조합이 남지 않게.
     */
    @PutMapping("/staff/accounts/{accountId}/menus")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<AccountMenuView> replace(@CurrentAccount AuthPrincipal me,
                                                @PathVariable Long accountId,
                                                @RequestBody MenuCodes request) {
        List<Menu> allowed = menuAccessService.replace(me, accountId, request.menuCodes());
        return ApiResponse.success(new AccountMenuView(accountId, !allowed.isEmpty(),
                allowed.stream().map(MenuView::from).toList()));
    }

    /** @param menuCodes 허용할 메뉴 코드. 비우면 제한 해제다 */
    public record MenuCodes(List<String> menuCodes) {
    }

    /**
     * @param enforceable 서버가 실제로 막을 수 있는 메뉴인가. {@code false} 면 <b>화면에서만</b>
     *                    감춰진다 — 경로가 등록되지 않은 메뉴다
     */
    public record MenuView(String code, String name, String parentCode,
                           short sortOrder, boolean enforceable) {

        static MenuView from(Menu m) {
            return new MenuView(m.getCode(), m.getName(), m.getParentCode(),
                    m.getSortOrder(), m.enforceable());
        }
    }

    /** @param restricted 제한이 걸려 있는가. {@code false} 면 역할 권한 그대로다 */
    public record AccountMenuView(Long accountId, boolean restricted, List<MenuView> menus) {
    }
}
