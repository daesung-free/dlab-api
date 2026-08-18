package com.dlab.api.kiosk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * DSA 호환 구획이 전용 예외 핸들러에 <b>빠짐없이 덮이는지</b> 확인한다.
 *
 * <p><b>왜 필요한가</b> — {@link DsaExceptionHandler}가 하위 패키지를 하나씩 나열한다.
 * 통째로 잡으면 DSA가 아닌 신규 구획({@code /api/v1/kiosk/**})까지 덮어 오류가
 * HTTP 200 + {@code code} 형식으로 나가기 때문이다.
 *
 * <p>그래서 <b>새 DSA 컨트롤러를 새 하위 패키지에 만들면 목록에 추가해야 하는데</b>,
 * 빠뜨려도 정상 응답은 멀쩡해서 <b>오류가 날 때까지 아무도 모른다.</b> 그때는 키오스크가
 * 우리 형식을 파싱하지 못해 화면이 깨진다. 이 테스트가 그 누락을 배포 전에 잡는다.
 */
@SpringBootTest
class DsaHandlerCoverageTest {

    /** 매핑 빈이 여러 개라(액추에이터 등) 이름으로 지목한다. */
    @Autowired @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    @Test
    @DisplayName("★ /kiosk·/auth 컨트롤러는 전부 DSA 예외 핸들러 범위 안에 있다")
    void everyDsaControllerIsCovered() {
        List<String> covered = Arrays.asList(
                DsaExceptionHandler.class.getAnnotation(RestControllerAdvice.class).basePackages());
        assertThat(covered).isNotEmpty();

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!isDsaPath(entry.getKey())) {
                continue;
            }
            String declaring = entry.getValue().getBeanType().getPackageName();
            assertThat(covered)
                    .as("%s 가 DsaExceptionHandler basePackages에 없다 — 이 엔드포인트만 "
                            + "오류 응답 형식이 달라져 키오스크 파싱이 깨진다",
                            entry.getValue().getBeanType().getName())
                    .anyMatch(pkg -> inPackage(declaring, pkg));
        }
    }

    @Test
    @DisplayName("★ 신규 구획(/api/v1/kiosk)은 DSA 핸들러에 덮이지 않는다 — 정상 형식이어야 한다")
    void nativeKioskIsNotCovered() {
        List<String> covered = Arrays.asList(
                DsaExceptionHandler.class.getAnnotation(RestControllerAdvice.class).basePackages());

        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            if (!paths(entry.getKey()).stream().anyMatch(p -> p.startsWith("/api/v1/kiosk"))) {
                continue;
            }
            HandlerMethod handler = entry.getValue();
            String declaring = handler.getBeanType().getPackageName();
            assertThat(covered)
                    .as("%s 는 신규 구획인데 DSA 예외 핸들러에 덮인다 — 오류가 HTTP 200 + code로 "
                            + "나가 정상 응답과 구분되지 않는다", handler.getBeanType().getName())
                    .noneMatch(pkg -> inPackage(declaring, pkg));
        }
    }

    /**
     * ★ 단순 {@code startsWith}로 비교하면 안 된다 — {@code ...kiosk.seat}가
     * {@code ...kiosk.seatleave}의 접두사라 신규 구획이 DSA로 잡힌다.
     * Spring도 내부적으로 점을 붙여 비교하므로 여기서도 같게 맞춘다.
     */
    private boolean inPackage(String declaring, String basePackage) {
        return declaring.equals(basePackage) || declaring.startsWith(basePackage + ".");
    }

    private boolean isDsaPath(RequestMappingInfo info) {
        return paths(info).stream()
                .anyMatch(p -> p.startsWith("/kiosk") || p.startsWith("/auth"));
    }

    private Set<String> paths(RequestMappingInfo info) {
        return info.getPathPatternsCondition() == null
                ? Set.of()
                : info.getPathPatternsCondition().getPatternValues();
    }
}
