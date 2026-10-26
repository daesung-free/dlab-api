package com.dlab.integration.fcm;

import java.nio.file.Files;
import java.nio.file.Path;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * FCM 자격증명이 <b>실제로 쓸 수 있는가</b>.
 *
 * <h2>★★ 키를 못 읽어도 앱은 떠야 한다</h2>
 * 처음에는 경로가 틀리면 <b>기동에서 끊었다</b>. 틀린 판단이었다 —
 * 푸시는 보조 채널인데 그걸로 <b>키오스크·앱·관리자 웹이 전부 멈춘다.</b>
 * 놓치면 안 되는 알림은 알림톡이고, 푸시가 안 가는 것보다
 * <b>API 가 죽는 쪽이 훨씬 나쁘다.</b>
 *
 * <h2>⚠️ 실제로 한 번 걸렸다 (2026-10-26)</h2>
 * 키 파일을 올리고 {@code chmod 600} 을 걸었는데 <b>컨테이너는 uid 999 로 돌고
 * 파일 주인은 호스트 사용자였다</b> — 호스트에서는 보이는데 컨테이너에서는 못 읽는다.
 * 그대로 배포했으면 <b>운영이 기동에 실패해 크래시 루프</b>였다.
 * 그래서 「경로가 있는가」가 아니라 <b>「읽히는가」</b>를 본다.
 *
 * <p>읽히지 않으면 빈이 만들어지지 않고 발송 경로가
 * {@code SKIPPED "발송 구현체 없음: FCM_PUSH"} 로 남긴다 — 알림톡과 같은 규약이다.
 * ★ <b>대신 기동 로그에 크게 남긴다.</b> 조용히 넘기면 「푸시가 왜 안 오지」를
 * 한참 뒤에 알게 된다.
 */
@Slf4j
public class FcmCredentialsCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String path = context.getEnvironment().getProperty("fcm.credentials-path");
        if (path == null || path.isBlank()) {
            // 로컬·CI 의 정상 상태다 — 경고하지 않는다
            return false;
        }
        if (!Files.isReadable(Path.of(path))) {
            log.error("★★ FCM 키를 읽을 수 없어 푸시 발송을 끈다: {} "
                    + "— 컨테이너 사용자가 읽을 수 있는지 확인할 것(파일 주인·권한). "
                    + "푸시는 SKIPPED 로 남고 다른 기능은 정상 동작한다.", path);
            return false;
        }
        return true;
    }
}
