package com.dlab.api.admin.appconfig;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/** 관리자 앱 설정 요청 DTO. */
public final class AdminAppConfigRequests {

    /** {@code 1.2.3} 형태. 마디는 2~4개까지 받는다. */
    private static final String VERSION_REGEX = "^\\d+(\\.\\d+){1,3}$";

    private AdminAppConfigRequests() {
    }

    /** {@code null}은 "변경하지 않음"이다 — 점검만 켜려다 최소 버전이 지워지면 안 된다. */
    public record UpdateVersions(
            @Pattern(regexp = VERSION_REGEX, message = "버전 형식이 올바르지 않습니다. (예: 1.2.0)")
            String minVersion,
            @Pattern(regexp = VERSION_REGEX, message = "버전 형식이 올바르지 않습니다. (예: 1.2.0)")
            String latestVersion) {
    }

    public record ChangeMaintenance(
            @NotNull(message = "점검 여부는 필수입니다.") Boolean maintenance,
            @Size(max = 300) String message,
            Instant until) {
    }

    public record CreateTerms(
            /**
             * 지점 전용 약관이면 지정한다. 비우면 전 지점 공통.
             *
             * <p>장학 동의서처럼 문구에 지점명이 들어가는 것이 있다 —
             * 지점본이 있으면 같은 코드의 공통본을 대체한다.
             */
            Long academyId,
            @NotBlank(message = "약관 코드는 필수입니다.") @Size(max = 30) String code,
            @NotBlank(message = "버전은 필수입니다.") @Size(max = 20) String version,
            @NotBlank(message = "제목은 필수입니다.") @Size(max = 100) String title,
            @NotBlank(message = "본문은 필수입니다.") String content,
            /** 생략하면 필수 약관으로 본다 — 선택 약관은 명시해야 한다. */
            Boolean required,
            Instant effectiveAt) {
    }
}
