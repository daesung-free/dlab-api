package com.dlab.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 설정이 컨테이너까지 전달되는지 지킨다.
 *
 * <h2>★★ 같은 함정을 세 번 밟았다</h2>
 * {@code application.yml} 이 {@code ${어떤변수}} 로 읽는데 <b>compose 의
 * {@code environment} 에 적지 않으면</b>, 운영 {@code .env} 에 값을 넣어도
 * <b>컨테이너에는 빈 값이 전달된다.</b> 그러면 그 기능만 조용히 꺼진 채로 돈다.
 *
 * <pre>
 * CORS_ALLOWED_ORIGINS            화면이 API 를 못 불렀다
 * HOMEPAGE_INTEGRATION_API_KEY    입학신청이 전부 401 이었다
 * FCM_CREDENTIALS_PATH            푸시가 꺼진 채로 배포됐다 (2026-10-26)
 * </pre>
 *
 * <p>세 번 다 <b>배포는 성공하고 앱도 멀쩡히 떴다</b> — 그래서 찾기 어려웠다.
 * 사람이 기억하는 대신 <b>CI 가 잡게 한다.</b>
 *
 * <h2>⚠️ 이 테스트가 깨졌다면</h2>
 * 새 환경변수를 {@code application.yml} 에만 넣은 것이다.
 * {@code deploy/docker-compose.yml} 의 app 서비스 {@code environment} 에도 추가할 것.
 */
class ComposeEnvCoverageTest {

    /** {@code ${VAR}} · {@code ${VAR:default}} 를 찾는다. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*)");

    /**
     * compose 가 아니라 다른 경로로 들어오는 값.
     *
     * <p>⚠️ 여기에 추가하기 전에 <b>정말 그런지 확인할 것</b> — 빠뜨린 변수를 여기 넣으면
     * 이 테스트가 하는 일이 없어진다.
     */
    private static final Set<String> NOT_FROM_COMPOSE = Set.of(
            // compose 가 POSTGRES_* 에서 조립해 직접 넣는다(리터럴이라 ${} 가 아니다)
            "DB_URL", "DB_USERNAME", "DB_PASSWORD",
            // 컨테이너 네트워크 이름으로 고정돼 있다
            "REDIS_HOST", "REDIS_PORT");

    @Test
    @DisplayName("★★ application.yml 이 읽는 환경변수가 compose 에도 적혀 있다 — 없으면 조용히 꺼진다")
    void everyPlaceholderIsPassedThrough() throws IOException {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"));

        Set<String> missing = new LinkedHashSet<>();
        for (Path yml : configFiles()) {
            Matcher m = PLACEHOLDER.matcher(Files.readString(yml));
            while (m.find()) {
                String name = m.group(1);
                if (NOT_FROM_COMPOSE.contains(name)) {
                    continue;
                }
                if (!assignedInCompose(compose, name)) {
                    missing.add(name + "  (" + yml.getFileName() + ")");
                }
            }
        }

        assertThat(missing)
                .as("deploy/docker-compose.yml 의 app 서비스 environment 에 추가할 것. "
                        + "★ .env 에만 넣으면 컨테이너에 전달되지 않는다")
                .isEmpty();
    }

    /**
     * ★★ <b>숫자 기본값을 가진 변수는 compose 에도 기본값이 있어야 한다.</b>
     *
     * <h2>⚠️ 이게 2026-10-30 배포를 실패시켰다</h2>
     * {@code AUDIT_RETENTION_YEARS} 를 compose 에 <b>{@code ${VAR:-} } 로</b> 적었다 —
     * 다른 변수들을 그대로 따른 것이다. 그런데 <b>compose 의 {@code :-} 는 「없음」을
     * 「있지만 빈 값」으로 바꾼다.</b>
     *
     * <p>★ 그러면 {@code application.yml} 의 {@code ${VAR:3}} 기본값이
     * <b>적용되지 않는다</b> — 빈 문자열이 이미 「값」이라서다. 그 빈 문자열을
     * {@code int} 에 바인딩하다 <b>기동이 깨졌다.</b>
     *
     * <p>⚠️ <b>문자열 변수는 안 깨진다</b> — 빈 값이 그냥 빈 값이다. 그래서
     * 다른 변수들을 따라 적는 것이 <b>숫자에서만 사고가 된다.</b>
     *
     * <h2>★ CI 가 못 잡는다 — 그래서 테스트가 필요하다</h2>
     * 테스트와 CI 는 <b>그 환경변수를 아예 안 쓰므로</b> Spring 기본값으로 잘 뜬다.
     * <b>운영 {@code .env} 에만 없을 때</b> 깨지는데, 그 상태를 재현하는 것이
     * <b>compose 파일을 읽는 이 검사</b>다.
     */
    @Test
    @DisplayName("★★ 숫자 기본값 변수는 compose 에도 기본값이 있다 — 빈 문자열이면 기동이 깨진다")
    void numericDefaultsHaveComposeDefaults() throws IOException {
        String compose = Files.readString(Path.of("deploy/docker-compose.yml"));
        // application.yml 의 ${VAR:숫자} — 숫자 기본값을 가진 변수만 고른다
        Pattern numeric = Pattern.compile("\\$\\{([A-Z_][A-Z0-9_]*):(\\d+)\\}");

        Set<String> broken = new LinkedHashSet<>();
        for (Path yml : configFiles()) {
            Matcher m = numeric.matcher(Files.readString(yml));
            while (m.find()) {
                String name = m.group(1);
                if (NOT_FROM_COMPOSE.contains(name)) {
                    continue;
                }
                // compose 가 ${NAME:-} 로 넘기면 빈 문자열이 전달된다
                if (Pattern.compile("^\\s*" + name + ":\\s*\\$\\{" + name + ":-\\}",
                                Pattern.MULTILINE)
                        .matcher(compose).find()) {
                    broken.add(name + "  (" + yml.getFileName() + ")");
                }
            }
        }

        assertThat(broken)
                .as("★ compose 에서 ${NAME:-} 를 ${NAME:-기본값} 으로 바꿀 것. "
                        + "⚠️ 빈 문자열은 「값」이라 application.yml 의 기본값을 덮는다 — "
                        + "숫자 바인딩이 깨져 ★ 기동이 실패한다(2026-10-30 실제 발생)")
                .isEmpty();
    }

    /**
     * compose 에 <b>실제로 대입돼 있는가</b>.
     *
     * <p>⚠️ 단순히 이름이 들어 있는지만 보면 <b>주석에 적혀 있어도 통과한다</b> —
     * 실제로 그렇게 통과시켰다. 설명하는 주석과 전달하는 줄은 다르다.
     * 그래서 {@code NAME: ${NAME} } 꼴을 찾는다.
     */
    private boolean assignedInCompose(String compose, String name) {
        return Pattern.compile("^\\s*" + name + ":\\s*\\$\\{" + name,
                        Pattern.MULTILINE)
                .matcher(compose)
                .find();
    }

    private List<Path> configFiles() throws IOException {
        try (var paths = Files.list(Path.of("src/main/resources"))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("application")
                            && p.getFileName().toString().endsWith(".yml"))
                    .toList();
        }
    }
}
