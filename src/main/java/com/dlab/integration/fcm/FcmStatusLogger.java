package com.dlab.integration.fcm;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 기동 시 FCM 상태를 한 줄로 남긴다.
 *
 * <h2>★★ 꺼져 있을 때도 떠야 한다</h2>
 * 환경변수를 빠뜨려 푸시가 꺼진 채로 배포했는데 <b>기동 로그가 조용해서</b> 원인을 찾는 데
 * 시간이 걸렸다(2026-10-26). 켜졌을 때만 로그가 있으면 <b>「없음」과 「문제」가 구분되지
 * 않는다</b> — 둘 다 아무 말이 없기 때문이다.
 *
 * <p>★ 이 빈은 <b>조건 없이 항상 만들어진다.</b> {@link FirebaseConfig} 는 자격증명이
 * 쓸 수 있을 때만 생기므로 <b>꺼진 사실을 말해 줄 수 없다.</b>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FcmStatusLogger {

    private final FcmProperties properties;

    @PostConstruct
    void logStatus() {
        String path = properties.credentialsPath();
        if (path == null || path.isBlank()) {
            log.warn("FCM 자격증명이 없어 푸시 발송이 꺼져 있다 — 푸시는 SKIPPED 로 남는다. "
                    + "운영이라면 FCM_CREDENTIALS_PATH 를 확인할 것. "
                    + "⚠️ .env 에만 넣어도 전달되지 않는다 — compose 의 environment 에도 있어야 한다.");
            return;
        }
        if (!FcmCredentialsCondition.usable(path)) {
            log.error("★★ FCM 키를 읽을 수 없어 푸시 발송을 끈다: {} — "
                    + "컨테이너 사용자가 읽을 수 있는지 확인할 것(파일 주인·권한). "
                    + "다른 기능은 정상 동작한다.", path);
            return;
        }
        // 켜진 경우의 활성화 로그는 FirebaseConfig 가 project id 까지 담아 남긴다
        log.info("FCM 자격증명 확인됨: {}", path);
    }
}
