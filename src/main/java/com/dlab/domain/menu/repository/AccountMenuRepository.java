package com.dlab.domain.menu.repository;

import com.dlab.domain.menu.entity.AccountMenu;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountMenuRepository extends JpaRepository<AccountMenu, AccountMenu.Key> {

    @Query("""
            SELECT am FROM AccountMenu am
            JOIN FETCH am.menu m
            WHERE am.account.id = :accountId
            ORDER BY m.sortOrder, m.id
            """)
    List<AccountMenu> findByAccountId(Long accountId);

    void deleteByAccountId(Long accountId);

    /**
     * 그 권한을 <b>명시적으로 부여받은</b> 계정 — 알림 수신 대상 해석용.
     *
     * <h2>★★ 역할 기본값으로는 주지 않는다</h2>
     * {@code RoleMenuDefaults} 는 <b>표에 없는 메뉴를 전 역할에 보인다</b>(새 메뉴가 조용히
     * 사라지는 것보다 보이는 편이 낫기 때문이다). 그 규칙을 알림 수신에 그대로 쓰면
     * <b>개별 설정이 없는 모든 계정이 수신 대상이 된다</b> — 아침 문자가 전 직원에게 나간다.
     *
     * <p>그래서 여기는 {@code account_menu} 행이 <b>실제로 있는</b> 계정만 본다.
     * 「누가 받을지 최고관리자가 체크해서 정한다」는 요구사항과도 그게 맞다.
     *
     * <p>지점 범위는 계정 소속을 따른다 — 직원·강사의 지점이고, 둘 다 아니면
     * (학생·학부모 계정) 지점이 없어 결과에서 빠진다.
     *
     * @param academyId 그 지점 담당만. {@code null} 이면 전 지점 권한자만 걸러 쓸 수 있게
     *                  <b>지점이 다른 계정까지</b> 함께 나온다 — 호출부가 가린다
     */
    @Query("""
            SELECT am.account FROM AccountMenu am
            JOIN am.menu m
            JOIN am.account a
            LEFT JOIN a.employee e
            LEFT JOIN a.teacher t
            WHERE m.code = :menuCode
              AND a.status = com.dlab.domain.user.entity.AccountStatus.ACTIVE
              AND a.withdrawnAt IS NULL
              AND (:academyId IS NULL
                   OR e.academy.id = :academyId OR t.academy.id = :academyId)
            """)
    List<com.dlab.domain.user.entity.Account> findAccountsWithMenu(
            @Param("menuCode") String menuCode,
            @Param("academyId") Long academyId);
}
