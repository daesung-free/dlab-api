package com.dlab.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API 스펙 자동생성.
 *
 * <p><b>이 레포가 스펙의 단일 진실 공급원이다</b>(CLAUDE.md §6-4).
 * {@code dlab-admin-web}은 openapi-typescript로, {@code dlab-app}은 openapi-generator로
 * 여기서 타입을 뽑아 쓴다 — 다른 레포에 스펙을 수동 복붙하지 않는다.
 *
 * <p><b>그룹을 앱·관리자로 나눈다.</b> 한 문서에 다 넣으면 앱 개발자가 관리자 API까지
 * 타입으로 생성하게 되고, 그러면 앱 번들에 쓰지도 않는 관리자 스키마가 들어간다.
 *
 * <p><b>DSA 호환 구획({@code /auth/**}, {@code /kiosk/**})은 문서에서 제외한다.</b>
 * 키오스크는 이 문서를 보지 않고(이미 구현이 고정돼 있다), 응답 형식도
 * {@code ApiResponse}가 아니라 섞이면 오해를 부른다. 계약은 {@code docs/dsa-compat.md}가 정본이다.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI dlabOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("D.Lab API")
                        .version("v1")
                        .description("""
                                학생·학부모 앱과 관리자 웹이 사용하는 API.

                                키오스크가 호출하는 DSA 호환 구획(/auth/**, /kiosk/**)은
                                이 문서에 포함되지 않는다 — docs/dsa-compat.md 참고.
                                """))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    @Bean
    public GroupedOpenApi appApi() {
        return GroupedOpenApi.builder()
                .group("app")
                .pathsToMatch("/api/v1/app/**")
                .build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .pathsToMatch("/api/v1/admin/**")
                .build();
    }
}
