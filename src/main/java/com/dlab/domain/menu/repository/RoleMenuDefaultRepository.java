package com.dlab.domain.menu.repository;

import com.dlab.common.security.Role;
import com.dlab.domain.menu.entity.RoleMenuDefault;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RoleMenuDefaultRepository extends JpaRepository<RoleMenuDefault, Long> {

    List<RoleMenuDefault> findAllByDeletedFalse();

    Optional<RoleMenuDefault> findByRoleAndMenuIdAndDeletedFalse(Role role, Long menuId);

    /**
     * ★ 설정이 하나라도 있는 메뉴 코드.
     *
     * <p>★★ <b>「표에 없는 메뉴는 전 역할에 보인다」 판정의 근거</b>다.
     * ⚠️ 이것 없이 역할별 행만 보면 <b>「설정이 없다」와 「그 역할엔 안 보인다」가
     * 구분되지 않는다.</b>
     */
    @Query("""
            select distinct d.menu.code
              from RoleMenuDefault d
             where d.deleted = false
            """)
    List<String> findConfiguredMenuCodes();
}
