package com.dlab.api.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.Role;
import com.dlab.domain.menu.repository.MenuRepository;
import com.dlab.domain.menu.service.RoleMenuDefaults;
import com.dlab.domain.menu.service.RoleMenuMatrixService;
import jakarta.persistence.EntityManager;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★★ 권한 매트릭스를 <b>데이터로 옮긴 것</b>의 가드 (디랩 1029 8-2).
 *
 * <h2>★ 가장 중요한 것 — 옮기면서 값이 바뀌지 않았나</h2>
 * 110행을 <b>기계로 뽑아</b> 마이그레이션에 넣었다. ⚠️ 한 칸이 틀려도
 * <b>화면에서만 드러난다</b> — 그래서 <b>{@code BY_MENU} 와 테이블을 대조</b>한다.
 */
@SpringBootTest
@Transactional
class RoleMenuMatrixTest {

    @Autowired RoleMenuDefaults roleMenuDefaults;
    @Autowired RoleMenuMatrixService matrixService;
    @Autowired MenuRepository menuRepository;
    @Autowired EntityManager em;

    /**
     * ★★ <b>테이블이 {@code BY_MENU} 와 같다.</b>
     *
     * <p>⚠️ 마이그레이션이 110행을 넣었고 그 출처가 {@code BY_MENU} 다 —
     * <b>옮기면서 한 칸이라도 달라지면 여기서 걸린다.</b>
     */
    @Test
    @DisplayName("★★ 테이블이 BY_MENU 와 같다 — 옮기면서 한 칸이 틀려도 화면에서만 드러난다")
    void tableMatchesInitialMatrix() {
        var expected = RoleMenuDefaults.initialMatrix();
        var actual = matrixService.matrix().stream()
                .filter(RoleMenuMatrixService.MatrixRow::configured)
                .collect(java.util.stream.Collectors.toMap(
                        RoleMenuMatrixService.MatrixRow::code,
                        RoleMenuMatrixService.MatrixRow::roles));

        assertThat(actual.keySet())
                .as("★ 설정이 있는 메뉴 목록이 같다")
                .containsExactlyInAnyOrderElementsOf(expected.keySet());

        expected.forEach((code, roles) ->
                assertThat(actual.get(code))
                        .as("★ %s 의 역할 집합", code)
                        .containsExactlyInAnyOrderElementsOf(roles));
    }

    /**
     * ★★ <b>표에 없는 메뉴는 전 역할에 보인다.</b>
     *
     * <p>⚠️ 새 메뉴가 <b>조용히 사라지면 기능이 없는 줄 안다</b>(클래스 주석 그대로).
     */
    @Test
    @DisplayName("★★ 표에 없는 메뉴는 전 역할에 보인다 — 조용히 사라지면 기능이 없는 줄 안다")
    void unconfiguredMenuIsVisibleToAll() {
        assertThat(roleMenuDefaults.visibleTo("없는메뉴코드", Set.of(Role.READONLY)))
                .isTrue();
    }

    /** ★ 판정이 테이블을 읽는다 — 끄면 바로 안 보인다. */
    @Test
    @DisplayName("★ 판정이 테이블을 읽는다 — 칸을 끄면 바로 반영된다")
    void decisionReadsTheTable() {
        var menu = menuRepository.findByCode("pg-site").orElseThrow();
        // ★ 처음에는 최고관리자만 보인다
        assertThat(roleMenuDefaults.visibleTo("pg-site", Set.of(Role.SUPER_ADMIN))).isTrue();
        assertThat(roleMenuDefaults.visibleTo("pg-site", Set.of(Role.BRANCH_ADMIN))).isFalse();

        matrixService.change(menu.getId(), Role.BRANCH_ADMIN, true);
        em.flush();

        assertThat(roleMenuDefaults.visibleTo("pg-site", Set.of(Role.BRANCH_ADMIN)))
                .as("★ 켜면 바로 보인다")
                .isTrue();
    }

    /**
     * ★★ <b>마지막 역할은 끌 수 없다.</b>
     *
     * <p>⚠️ 「설정이 없는 메뉴는 전 역할에 보인다」가 규칙이라, 마지막 행을 지우면
     * <b>끈 것과 반대로 모두에게 보인다.</b>
     */
    @Test
    @DisplayName("★★ 마지막 역할은 끌 수 없다 — 지우면 끈 것과 반대로 모두에게 보인다")
    void cannotTurnOffTheLastRole() {
        var menu = menuRepository.findByCode("pg-site").orElseThrow();
        // pg-site 는 HEAD_OFFICE 하나뿐이다
        assertThatThrownBy(() ->
                matrixService.change(menu.getId(), Role.SUPER_ADMIN, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("마지막 역할");
    }

    /**
     * ★ 끈 칸은 <b>그 역할에만</b> 영향을 준다.
     */
    @Test
    @DisplayName("★ 한 역할을 끄면 나머지는 그대로다")
    void turningOffOneRoleKeepsOthers() {
        var menu = menuRepository.findByCode("grade").orElseThrow();
        // grade 는 WITH_TEACHER — 최고·지점·담임
        matrixService.change(menu.getId(), Role.TEACHER, false);
        em.flush();

        assertThat(roleMenuDefaults.visibleTo("grade", Set.of(Role.TEACHER))).isFalse();
        assertThat(roleMenuDefaults.visibleTo("grade", EnumSet.of(Role.BRANCH_ADMIN)))
                .as("★ 지점 관리자는 그대로 보인다")
                .isTrue();
    }

    /**
     * ★ 설정이 없는 메뉴도 <b>목록에 내려준다</b>.
     *
     * <p>⚠️ 빼면 화면이 <b>그 메뉴가 없는 줄 안다.</b>
     */
    @Test
    @DisplayName("★ 설정이 없는 메뉴도 목록에 내려준다 — 빼면 그 메뉴가 없는 줄 안다")
    void matrixIncludesUnconfigured() {
        long total = menuRepository.findAllOrdered().size();
        assertThat(matrixService.matrix()).hasSize((int) total);
        assertThat(matrixService.matrix())
                .anyMatch(row -> !row.configured());
    }
}
