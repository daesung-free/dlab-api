package com.dlab.domain.search.repository;

import com.dlab.domain.search.entity.SavedSearch;
import com.dlab.domain.search.entity.SearchType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {

    List<SavedSearch> findByAccountIdAndSearchTypeAndDeletedFalseOrderByNameAsc(
            Long accountId, SearchType searchType);

    Optional<SavedSearch> findByAccountIdAndSearchTypeAndNameAndDeletedFalse(
            Long accountId, SearchType searchType, String name);
}
