package com.dlab.domain.menu.repository;

import com.dlab.domain.menu.entity.AccountMenu;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AccountMenuRepository extends JpaRepository<AccountMenu, AccountMenu.Key> {

    @Query("""
            SELECT am FROM AccountMenu am
            JOIN FETCH am.menu m
            WHERE am.account.id = :accountId
            ORDER BY m.sortOrder, m.id
            """)
    List<AccountMenu> findByAccountId(Long accountId);

    void deleteByAccountId(Long accountId);
}
