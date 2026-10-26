package com.dlab.integration.fcm;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * Firebase 초기화.
 *
 * <h2>★ 자격증명을 쓸 수 없으면 빈을 만들지 않는다</h2>
 * 판정은 {@link FcmCredentialsCondition} 한 곳에 있다 — <b>경로가 있는가가 아니라
 * 읽히는가</b>를 본다. {@code FcmPushSender} 도 같은 조건을 쓴다.
 *
 * <h2>★ FirebaseApp 은 프로세스에 하나다</h2>
 * 이미 초기화돼 있으면 그것을 쓴다. 두 번 부르면
 * {@code FirebaseApp name [DEFAULT] already exists} 로 터지는데,
 * 테스트가 컨텍스트를 여러 번 띄우면 실제로 만난다.
 */
@Slf4j
@Configuration
@Conditional(FcmCredentialsCondition.class)
public class FirebaseConfig {

    @Bean
    public FirebaseMessaging firebaseMessaging(FcmProperties properties) throws IOException {
        // ★ 읽히는지는 FcmCredentialsCondition 이 이미 봤다 — 여기 오면 읽을 수 있다
        Path path = Path.of(properties.credentialsPath());

        FirebaseApp app = FirebaseApp.getApps().stream()
                .filter(a -> FirebaseApp.DEFAULT_APP_NAME.equals(a.getName()))
                .findFirst()
                .orElseGet(() -> initialize(path));

        if (properties.dryRunOrDefault()) {
            log.warn("★ FCM 이 검증 전용(dry-run)이다 — 단말로 실제 발송되지 않는다.");
        }
        return FirebaseMessaging.getInstance(app);
    }

    private FirebaseApp initialize(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            FirebaseApp app = FirebaseApp.initializeApp(FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build());
            log.info("FCM 연동이 활성화됐다: project={}", app.getOptions().getProjectId());
            return app;
        } catch (IOException e) {
            throw new IllegalStateException("FCM 초기화 실패: " + path.toAbsolutePath(), e);
        }
    }
}
