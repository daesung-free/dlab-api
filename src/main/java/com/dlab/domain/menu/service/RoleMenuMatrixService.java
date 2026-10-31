package com.dlab.domain.menu.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.Role;
import com.dlab.domain.menu.entity.Menu;
import com.dlab.domain.menu.entity.RoleMenuDefault;
import com.dlab.domain.menu.repository.MenuRepository;
import com.dlab.domain.menu.repository.RoleMenuDefaultRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★★ <b>권한 매트릭스를 편집한다</b> (디랩 1029 8-2).
 *
 * <p>디랩: <b>「지금처럼 두어도 될 것 같습니다. 권한 매트릭스 화면에서의 수정이
 * 가능할까요?」</b> — 화면은 <b>「설명용이며 수정할 수 없습니다」</b> 였고 값이
 * <b>코드</b>에 있었다.
 *
 * <h2>★ 칸은 「보인다 / 안 보인다」 하나다</h2>
 * ⚠️ <b>조회/편집으로 나누지 않는다</b>(1029 1-2 결정) — 그 두 칸을 표에 남기면
 * <b>「조회만 열었다」가 되는 줄 알게 된다.</b>
 *
 * <h2>⚠️ 메뉴는 권한이 아니다</h2>
 * 표에서 켜도 <b>서버 {@code @PreAuthorize} 가 막으면 못 쓴다.</b> 화면에
 * <b>「메뉴 표시 설정이며, 기능 권한은 역할에 따라 정해집니다」</b>를 적어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleMenuMatrixService {

    private final RoleMenuDefaultRepository repository;
    private final MenuRepository menuRepository;

    /**
     * ★ 지금 표 — 메뉴마다 <b>어느 역할에 보이는가</b>.
     *
     * <p>★ <b>설정이 없는 메뉴도 내려준다</b>({@code configured = false}) —
     * ⚠️ 빼면 화면이 <b>그 메뉴가 없는 줄 안다.</b> 그리고 그 메뉴는
     * <b>전 역할에 보인다</b>는 것을 화면이 알아야 한다.
     */
    @Transactional(readOnly = true)
    public List<MatrixRow> matrix() {
        Map<String, Set<Role>> byCode = new TreeMap<>();
        for (RoleMenuDefault row : repository.findAllByDeletedFalse()) {
            byCode.computeIfAbsent(row.getMenu().getCode(), k -> java.util.EnumSet.noneOf(Role.class))
                    .add(row.getRole());
        }
        return menuRepository.findAllOrdered().stream()
                .map(menu -> {
                    Set<Role> roles = byCode.get(menu.getCode());
                    return new MatrixRow(menu.getId(), menu.getCode(), menu.getName(),
                            roles == null ? Set.of() : roles,
                            roles != null);
                })
                .toList();
    }

    /**
     * ★★ 한 칸을 켜거나 끈다.
     *
     * <h2>⚠️ 마지막 역할을 끄면 「전 역할에 보인다」로 뒤집힌다</h2>
     * 「행이 없는 메뉴는 전 역할에 보인다」가 규칙이므로, <b>그 메뉴의 마지막 행을
     * 지우면 모두에게 보이게 된다</b> — 끈 사람의 의도와 정반대다. 그래서 <b>막는다.</b>
     *
     * <p>★ 「아무에게도 안 보이게」가 필요하면 <b>메뉴 자체를 끄는 쪽</b>이다.
     */
    @Transactional
    public void change(Long menuId, Role role, boolean visible) {
        Menu menu = menuRepository.findById(menuId)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "메뉴를 찾을 수 없습니다."));

        var existing = repository.findByRoleAndMenuIdAndDeletedFalse(role, menuId);
        if (visible) {
            if (existing.isEmpty()) {
                repository.save(new RoleMenuDefault(role, menu));
            }
            return;
        }

        if (existing.isEmpty()) {
            return;
        }
        long remaining = repository.findAllByDeletedFalse().stream()
                .filter(r -> r.getMenu().getId().equals(menuId))
                .filter(r -> r.getRole() != role)
                .count();
        if (remaining == 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "마지막 역할은 끌 수 없습니다. 설정이 하나도 없는 메뉴는 "
                            + "전 역할에 보이게 되어 끈 것과 반대가 됩니다. "
                            + "아무에게도 보이지 않게 하려면 메뉴 자체를 끄세요.");
        }
        existing.get().markDeleted();
        log.info("권한 매트릭스 변경: menu={}, role={}, 보이기={}", menu.getCode(), role, visible);
    }

    /**
     * @param roles      이 메뉴가 보이는 역할
     * @param configured ★ {@code false} 면 <b>설정이 없다</b> — 전 역할에 보인다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "RoleMenuMatrixRow")
    public record MatrixRow(Long menuId, String code, String name,
                            Set<Role> roles, boolean configured) {
    }
}
