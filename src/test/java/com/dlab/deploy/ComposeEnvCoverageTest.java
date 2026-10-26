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
