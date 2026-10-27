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
import java.util.Set;
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
    private final com.dlab.common.security.SessionCutoff sessionCutoff;
    private final AccountMenuRepository accountMenuRepository;
    private final com.dlab.domain.menu.repository.FavoriteMenuRepository favoriteMenuRepository;
    private final AccountRepository accountRepository;
    private final com.dlab.domain.user.repository.AccountRoleRepository accountRoleRepository;

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

    /**
     * 이 계정에 <b>화면 노출 제한</b>이 걸려 있나.
     *
     * <h2>★★ 동작 권한 행은 세지 않는다</h2>
     * {@code account_menu} 에 두 축이 섞여 있다 — <b>화면 노출</b>(사이드바에 보일 메뉴)과
     * <b>동작 권한</b>({@code action_only}, 예: 출근 기록 수정·아침 알림 수신).
     *
     * <p>행이 있는지만 보면 <b>동작 권한 하나를 켠 순간 그 계정 화면이 전부 사라진다</b> —
     * 제한이 걸린 것으로 판정되고, 그 목록에 화면이 하나도 없기 때문이다. 최고관리자가
     * 체크해서 정하라는 바로 그 두 권한이 그래서 쓸 수 없는 상태였다.
     */
    @Transactional(readOnly = true)
    public boolean restricted(Long accountId) {
        return !withoutActionOnly(allowedMenus(accountId)).isEmpty();
    }

    /**
     * 이 계정에 보여줄 메뉴.
     *
     * <p>계정별 설정이 있으면 그것이고, 없으면 <b>역할 기본값</b>이다
     * ({@link RoleMenuDefaults}).
     *
     * <h2>왜 역할 기본값이 필요한가</h2>
     * 예전에는 설정이 없는 계정에 카탈로그 전체를 내렸다. 그래서 담임 사이드바에
     * 급식·수납처럼 <b>애초에 열려 있지 않은 메뉴가 그대로 보였고</b>, 눌러 봐야 안 된다는
     * 것을 알게 됐다.
     *
     * <p><b>계정별 설정이 이긴다.</b> "계정 하나하나에 최고관리자가 고른다"는 0914 확정을
     * 깨지 않는다 — 기본값은 아무도 고르지 않았을 때만 쓰인다.
     */
    @Transactional(readOnly = true)
    public List<Menu> visibleMenus(AuthPrincipal me) {
        // ★ 동작 권한만 켜져 있는 계정은 "제한 없음"이다 — 아래 restricted() 설명 참고.
        //   화면 목록을 먼저 걸러서 보지 않으면 동작 권한 하나에 사이드바가 비어 버린다
        List<Menu> allowedScreens = withoutActionOnly(allowedMenus(me.accountId()));
        if (!allowedScreens.isEmpty()) {
            return allowedScreens;
        }
        return withoutActionOnly(catalog().stream()
                .filter(menu -> RoleMenuDefaults.visibleTo(menu.getCode(), me.roles()))
                .toList());
    }

    /**
     * 화면 없는 권한을 뺀다 — 사이드바·자주 쓰는 메뉴용.
     *
     * <p>★ <b>서버에서 뺀다.</b> 화면이 거르게 맡기면 한 곳에서 잊는 순간 <b>눌러도 갈 데가
     * 없는 항목이 사이드바에 생기고</b>, 쓰는 쪽은 그것을 고장으로 읽는다.
     * 권한 체크박스 목록은 {@link #catalog()}를 쓰므로 그쪽에는 그대로 나온다.
     */
    private static List<Menu> withoutActionOnly(List<Menu> menus) {
        return menus.stream().filter(menu -> !menu.isActionOnly()).toList();
    }

    /**
     * 역할을 모르는 경로용(내부 호출). <b>역할 기본값이 걸리지 않는다</b> —
     * 화면에 내려보내는 목록에는 {@link #visibleMenus(AuthPrincipal)}를 쓸 것.
     */
    @Transactional(readOnly = true)
    public List<Menu> visibleMenus(Long accountId) {
        List<Menu> allowed = allowedMenus(accountId);
        return allowed.isEmpty() ? catalog() : allowed;
    }

    /**
     * 그 계정의 <b>역할 기본 메뉴</b> — 사용자 관리 화면이 체크박스 초기 상태로 쓴다.
     *
     * <p>계정별 설정이 없을 때 실제로 내려가는 목록과 같다. 화면이 이걸로 미리 체크해 두면,
     * 저장 버튼을 눌렀다는 이유만으로 보이던 메뉴가 사라지지 않는다 —
     * 빈 체크 상태에서 저장하면 <b>제한 해제</b>가 되어 전체가 다시 열린다.
     */
    @Transactional(readOnly = true)
    public List<Menu> roleDefaults(Long accountId) {
        Set<Role> roles = accountRoleRepository.findRoleNamesByAccountId(accountId).stream()
                .map(Role::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        if (roles.isEmpty()) {
            return catalog();
        }
        return catalog().stream()
                .filter(menu -> RoleMenuDefaults.visibleTo(menu.getCode(), roles))
                .toList();
    }

    /**
     * ★★ 편집본대로 저장하면 <b>실제로 어떻게 열리는가</b> — 저장하지 않고 미리 본다.
     *
     * <h2>왜 필요한가</h2>
     * 계정별 메뉴 지정은 <b>보이는 것을 줄이는</b> 기능이고 <b>권한을 늘리지 못한다.</b>
     * 그런데 화면에서는 역할에 없는 메뉴도 체크가 되므로, 담당자는 열어 준 줄 알고
     * 저장한다. 그 계정으로 들어가면 <b>사이드바에는 보이고 누르면 403</b> 이다 —
     * 빈 화면도 아니고 오류 화면이라 <b>설정이 틀린 줄 안다.</b>
     * 2026-10-27 에 실제로 그 혼동이 있었다(입학예약을 조회 전용에 체크한 건).
     *
     * <h2>★ 결과는 두 축이다</h2>
     * <pre>
     * visible   편집본 기준 ★ 메뉴에 나오는가        (restricted + menuCodes 가 정한다)
     * level     FULL     역할 기본 노출에 있다 — 들어가면 열린다
     *           PARTIAL  ★ 역할 기본 노출에 없다 — 보이지만 막힐 수 있다
     * </pre>
     *
     * <p>★ <b>계산을 서버에서만 한다.</b> 화면이 같은 규칙을 또 구현하면 둘이 어긋나고,
     * 그 어긋남이 바로 이 문제의 뿌리였다(10/14 담임 시간표 — 메뉴는 열렸는데 그 화면이
     * 쓰는 API 가 403 이라 격자가 비었다).
     *
     * <h2>⚠️ PARTIAL 은 「역할 기본 노출」을 근거로 한 추정이다</h2>
     * API 권한은 컨트롤러마다 {@code @PreAuthorize} 로 흩어져 있어 <b>서버도 목록으로
     * 들고 있지 않다.</b> 지금 가진 가장 가까운 근거가 역할 기본 노출이고, 지금까지
     * 둘을 손으로 맞춰 왔다(성적 메뉴를 조회 전용에서 일부러 뺀 것이 그 예다).
     * <b>그래서 PARTIAL 은 「열린다/안 열린다」가 아니라 「확인이 필요하다」로 읽어야 한다.</b>
     *
     * @param restricted 편집본의 제한 여부. {@code false} 면 역할 기본값이 그대로 내려간다
     * @param menuCodes  편집본에서 체크된 메뉴
     */
    @Transactional(readOnly = true)
    public List<MenuOutcome> preview(Long accountId, boolean restricted,
                                     List<String> menuCodes) {
        Set<Role> roles = accountRoleRepository.findRoleNamesByAccountId(accountId).stream()
                .map(Role::valueOf)
                .collect(java.util.stream.Collectors.toSet());

        // 역할이 없는 계정은 기본값 판정이 성립하지 않는다 — 전부 보이는 것으로 둔다
        // (roleDefaults 가 같은 선택을 한다. 두 곳이 다르면 결과와 기본값이 어긋난다)
        boolean noRoles = roles.isEmpty();

        List<String> checked = menuCodes == null ? List.of() : menuCodes;
        return catalog().stream().map(menu -> {
            boolean byRole = noRoles || RoleMenuDefaults.visibleTo(menu.getCode(), roles);
            // ★ 동작 권한(action_only)은 restricted 와 무관하게 보낸 그대로다 — replace 와
            //   같은 규칙을 쓴다. 다르게 두면 미리보기와 저장 결과가 갈린다
            boolean visible = menu.isActionOnly()
                    ? checked.contains(menu.getCode())
                    : (restricted ? checked.contains(menu.getCode()) : byRole);
            return new MenuOutcome(menu.getCode(), menu.getName(), visible,
                    byRole ? OutcomeLevel.FULL : OutcomeLevel.PARTIAL,
                    byRole ? null : "이 역할에는 열려 있지 않습니다. 메뉴에는 보이지만 "
                            + "들어갔을 때 거부될 수 있습니다.");
        }).toList();
    }

    /** 편집본대로 저장했을 때 메뉴 하나가 어떻게 열리는가. */
    public record MenuOutcome(String code, String name, boolean visible,
                              OutcomeLevel level, String note) {
    }

    /** {@link #preview} 참고. {@code PARTIAL} 은 「확인이 필요하다」는 뜻이다. */
    public enum OutcomeLevel {
        FULL, PARTIAL
    }

    /**
     * 설정을 통째로 바꾼다.
     *
     * <h2>★ 「기본값 사용」과 「아무것도 못 봄」은 다른 뜻이다</h2>
     * 예전에는 <b>빈 목록 하나에 두 뜻이 겹쳐</b> 있었다 — 비우면 제한이 풀렸다. 그래서
     * 화면의 「전체 해제」를 누르고 저장하면 <b>의도와 정반대로 전부 열렸다</b>(프론트가 실제로
     * 겪었다). 이제 {@code restricted} 로 뜻을 명시받고 <b>추측하지 않는다.</b>
     *
     * <ul>
     *   <li>{@code restricted=false} — 화면 개별 설정을 지우고 역할 기본값을 쓴다</li>
     *   <li>{@code restricted=true} + 코드 목록 — 그 메뉴만 열린다</li>
     *   <li>{@code restricted=true} + 화면 메뉴 없음 — <b>거부한다</b>(아래)</li>
     * </ul>
     *
     * <h2>★★ 동작 권한은 화면 제한과 다른 축이다</h2>
     * {@code action_only} 메뉴(출근 기록 수정·아침 알림 수신)는 <b>{@code restricted} 와
     * 무관하게 보낸 목록 그대로 저장된다.</b> 같이 지우면 <b>「역할 기본값 화면 + 이 동작 권한」</b>을
     * 표현할 방법이 없어지는데, 그게 지점 관리자에게 가장 흔한 조합이다
     * (기본 화면을 그대로 쓰면서 아침 알림만 받는다).
     *
     * <p>⚠️ 그래서 <b>동작 권한을 끄려면 목록에서 빼서 보내야 한다</b> —
     * {@code restricted=false} 로 바꾸는 것만으로는 꺼지지 않는다.
     *
     * <p>★ <b>「직접 지정 + 0개」를 막는 이유</b> — 아무 메뉴도 못 보는 계정은 로그인해도 할
     * 일이 없다. 그 상황에서 맞는 조치는 <b>계정 정지</b>이고, 메뉴를 전부 빼는 것은 같은
     * 결과를 더 알아보기 어렵게 만든다(화면에는 "권한 없음"만 보이고 왜인지가 안 남는다).
     */
    @Transactional
    public List<Menu> replace(AuthPrincipal me, Long accountId,
                              boolean restricted, List<String> menuCodes) {
        if (!me.roles().contains(Role.SUPER_ADMIN)) {
            // 지점 관리자가 남의 노출 범위를 바꾸면 사실상 권한 관리가 지점으로 내려간다
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "메뉴 노출 설정은 최고관리자만 바꿀 수 있습니다.");
        }
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND));

        List<String> codes = menuCodes == null ? List.of() : menuCodes;
        List<Menu> menus = menuRepository.findByCodes(codes);
        if (menus.size() != codes.stream().distinct().count()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "없는 메뉴 코드가 있습니다. 메뉴 목록을 다시 받아 주세요.");
        }

        // ★★ 두 축을 나눈다 — 동작 권한은 화면 제한과 함께 지워지면 안 된다
        List<Menu> screens = menus.stream().filter(menu -> !menu.isActionOnly()).toList();
        List<Menu> actions = menus.stream().filter(Menu::isActionOnly).toList();

        if (restricted && screens.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "메뉴를 하나 이상 골라야 합니다. 아무것도 보여주지 않으려면 계정을 정지해 주세요.");
        }

        accountMenuRepository.deleteByAccountId(accountId);
        accountMenuRepository.flush();

        // 화면은 restricted 일 때만 남긴다. 동작 권한은 restricted 와 무관하게 보낸 그대로다 —
        // 그래서 "역할 기본값 화면 + 이 동작 권한"이 표현된다(지점 관리자에게 가장 흔한 조합이다)
        List<Menu> saved = restricted
                ? java.util.stream.Stream.concat(screens.stream(), actions.stream()).toList()
                : actions;
        saved.forEach(menu -> accountMenuRepository.save(new AccountMenu(account, menu)));

        // ★ 바뀐 노출 범위를 즉시 적용한다. 끊지 않으면 이미 발급된 토큰이 최대 1시간 동안
        //   옛 범위로 돌아, 담당자 눈에는 「설정했는데 안 바뀐다」로 보인다 — 그 증상이
        //   「역할에 없어서 403」과 구분되지 않아 2026-10-27 에 실제로 혼동이 있었다.
        sessionCutoff.revokeAll(accountId);
        return saved;
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
    public List<Menu> replaceFavorites(AuthPrincipal me, List<String> menuCodes) {
        Long accountId = me.accountId();
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

        // 역할 기본값까지 반영된 목록으로 거른다 — 담을 수 없는 메뉴를 담으면
        // 눌렀을 때 403 이 나는 칸이 대시보드에 남는다.
        // 화면 없는 권한은 이 목록에서 이미 빠져 있어 담으려 하면 거절된다
        List<Menu> visible = visibleMenus(me);
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
        // ★★ 화면 메뉴만 본다. 원시 행을 보면 **동작 권한 하나를 켠 계정이 모든 화면 API 에서
        //   403 을 받는다** — 제한이 걸린 것으로 판정되고 그 목록에 화면이 없기 때문이다.
        //   restricted()·visibleMenus() 와 같은 판정을 써야 세 곳이 어긋나지 않는다
        List<Menu> allowed = withoutActionOnly(allowedMenus(accountId));
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
