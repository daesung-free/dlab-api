package com.dlab.api.admin.student;

import com.dlab.domain.search.entity.SavedSearch;

/** 저장된 검색조건. {@code conditions}는 화면이 그대로 되돌려 받는 JSON 원본이다. */
public record SavedSearchResponse(Long id, String name, String conditions) {

    public static SavedSearchResponse from(SavedSearch s) {
        return new SavedSearchResponse(s.getId(), s.getName(), s.getConditions());
    }
}
