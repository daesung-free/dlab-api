package com.dlab.domain.menu.repository;

import com.dlab.domain.menu.entity.FavoriteMenu;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FavoriteMenuRepository extends JpaRepository<FavoriteMenu, FavoriteMenu.Key> {

    @Query("""
            SELECT f FROM FavoriteMenu f
            JOIN FETCH f.menu
            WHERE f.account.id = :accountId
            ORDER BY f.sortOrder, f.menu.id
            """)
    List<FavoriteMenu> findByAccountId(Long accountId);

    void deleteByAccountId(Long accountId);
}
