package com.dlab.integration.fcm;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

/**
 * FCM 자격증명 조건.
 *
 * <h2>★★ 키를 못 읽어도 앱은 떠야 한다</h2>
 * 「경로가 있는가」로 보면, <b>호스트에는 있는데 컨테이너가 못 읽는</b> 경우를 통과시켜
 * 기동에서 터진다 — 실제로 그 상태로 배포 직전까지 갔다(2026-10-26, `chmod 600` +
 * 컨테이너 uid 999). 푸시는 보조 채널이라 <b>API 가 죽는 쪽이 훨씬 나쁘다.</b>
 */
class FcmCredentialsConditionTest {

    private final FcmCredentialsCondition condition = new FcmCredentialsCondition();

    @Test
    @DisplayName("경로가 비어 있으면 끈다 — 로컬·CI 의 정상 상태다")
    void 경로가_없으면_끈다() {
        assertThat(matches(null)).isFalse();
        assertThat(matches("")).isFalse();
    }

    @Test
    @DisplayName("★★ 파일이 없으면 끈다 — 기동에서 터지지 않고 푸시만 꺼진다")
    void 파일이_없으면_끈다(@TempDir Path dir) {
        assertThat(matches(dir.resolve("없는파일.json").toString()))
                .as("경로가 있다고 통과시키면 운영이 크래시 루프에 빠진다")
                .isFalse();
    }

    @Test
    @DisplayName("읽을 수 있으면 켠다")
    void 읽히면_켠다(@TempDir Path dir) throws Exception {
        Path key = dir.resolve("fcm.json");
        Files.writeString(key, "{}");

        assertThat(matches(key.toString())).isTrue();
    }

    private boolean matches(String path) {
        MockEnvironment env = new MockEnvironment();
        if (path != null) {
            env.setProperty("fcm.credentials-path", path);
        }
        return condition.matches(new StubContext(env), null);
    }

    /** {@link ConditionContext} 는 환경만 쓰므로 나머지는 쓰지 않는다. */
    private record StubContext(Environment env) implements ConditionContext {

        @Override
        public org.springframework.beans.factory.support.BeanDefinitionRegistry getRegistry() {
            throw new UnsupportedOperationException();
        }

        @Override
        public org.springframework.beans.factory.config.ConfigurableListableBeanFactory
                getBeanFactory() {
            return null;
        }

        @Override
        public Environment getEnvironment() {
            return env != null ? env : new StandardEnvironment();
        }

        @Override
        public org.springframework.core.io.ResourceLoader getResourceLoader() {
            return null;
        }

        @Override
        public ClassLoader getClassLoader() {
            return getClass().getClassLoader();
        }
    }
}
