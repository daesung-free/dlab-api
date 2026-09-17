package com.dlab.domain.menu.repository;

import com.dlab.domain.menu.entity.Menu;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    @Query("SELECT m FROM Menu m WHERE m.deleted = false ORDER BY m.sortOrder, m.id")
    List<Menu> findAllOrdered();

    @Query("SELECT m FROM Menu m WHERE m.code IN :codes AND m.deleted = false")
    List<Menu> findByCodes(List<String> codes);

    Optional<Menu> findByCode(String code);
}
