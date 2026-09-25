package com.dlab.api.admin.student;

import com.dlab.domain.search.entity.SavedSearch;
import com.dlab.domain.search.entity.SearchType;

/**
 * 저장된 검색조건. {@code conditions}는 화면이 그대로 되돌려 받는 JSON 원본이다.
 *
 * @param searchType 어느 화면의 조건인가. 화면이 섞어 받지 않도록 함께 내린다
 */
public record SavedSearchResponse(Long id, SearchType searchType, String name, String conditions) {

    public static SavedSearchResponse from(SavedSearch s) {
        return new SavedSearchResponse(s.getId(), s.getSearchType(), s.getName(), s.getConditions());
    }
}
