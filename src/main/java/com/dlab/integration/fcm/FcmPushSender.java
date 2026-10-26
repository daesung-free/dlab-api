package com.dlab.integration.fcm;

import com.dlab.domain.appconfig.entity.PushToken;
import com.dlab.domain.appconfig.repository.PushTokenRepository;
import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.service.NotificationSender;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * FCM 푸시 발송.
 *
 * <h2>★ 수신 단위가 「번호」가 아니라 「기기」다</h2>
 * 알림톡은 전화번호 하나로 가지만 푸시는 <b>계정에 등록된 기기 토큰 전부</b>로 간다.
 * 한 사람이 폰·태블릿을 쓰면 토큰이 둘이다.
 *
 * <h2>★★ 계정이 없는 수신자에게는 보낼 수 없다</h2>
 * 입학 대기자·급식업체처럼 <b>번호만 있는 수신자</b>가 실제로 있다
 * ({@code ck_notification_recipient} 가 계정과 번호 중 하나만 있어도 허용한다).
 * 그런 건 푸시 대상이 아니므로 던진다 — 조용히 넘기면 <b>안 보낸 것이 성공으로 남는다.</b>
 *
 * <h2>★★ 죽은 토큰은 지운다</h2>
 * 앱을 지우거나 토큰이 갱신되면 FCM 이 {@code UNREGISTERED} 를 돌려준다.
 * 지우지 않으면 <b>그 계정 발송이 영구히 실패로 쌓이고</b>, 기기를 바꾼 사용자에게
 * 새 토큰이 생긴 뒤에도 실패가 섞여 「푸시가 안 간다」로 보인다.
 *
 * <h2>★★ 자격증명이 없으면 이 빈을 만들지 않는다</h2>
 * {@link FcmCredentialsCondition} 을 {@link FirebaseConfig} 와 <b>같이 쓴다.</b>
 * 설정에만 걸고 여기 빠뜨리면
 * {@code FirebaseMessaging} 빈이 없는 환경에서 <b>컨텍스트가 아예 뜨지 않는다</b> —
 * 로컬·CI 가 전부 깨진다(실제로 한 번 깨뜨렸다).
 * 빈이 없으면 발송 경로가 {@code SKIPPED "발송 구현체 없음: FCM_PUSH"} 로 남긴다.
 *
 * <h2>⚠️ 푸시가 도착한다고 보장되지 않는다</h2>
 * FCM 이 성공을 돌려줘도 단말 도착은 별개다. 특히 <b>iOS 는 APNs 인증키가 콘솔에
 * 올라가 있어야</b> 하고, 없으면 <b>발송은 성공으로 뜨는데 아무것도 오지 않는다.</b>
 * 그래서 놓치면 안 되는 알림(미등원)은 푸시가 아니라 알림톡이다.
 */
@Slf4j
@Component
@Conditional(FcmCredentialsCondition.class)
@RequiredArgsConstructor
public class FcmPushSender implements NotificationSender {

    private final FirebaseMessaging messaging;
    private final PushTokenRepository tokenRepository;
    private final FcmProperties properties;

    @Override
    public NotificationChannel channel() {
        return NotificationChannel.FCM_PUSH;
    }

    @Override
    @Transactional
    public void send(NotificationLog log, NotificationTemplate template) {
        if (log.getRecipient() == null) {
            throw new FcmException("푸시는 계정이 있어야 보낼 수 있습니다: logId=" + log.getId());
        }

        List<PushToken> tokens =
                tokenRepository.findByAccountIdAndDeletedFalse(log.getRecipient().getId());
        if (tokens.isEmpty()) {
            // ⚠️ 「앱을 안 깐 사람」도 여기로 온다. 실패로 남기는 것이 맞다 —
            //    성공으로 두면 「보냈다」가 되어 안 간 사실이 사라진다
            throw new FcmException("등록된 기기가 없습니다: accountId="
                    + log.getRecipient().getId() + ", event=" + log.getEventCode());
        }

        Notification notification = Notification.builder()
                .setTitle(log.getTitle())
                .setBody(log.getBody())
                .build();

        if (tokens.size() == 1) {
            sendOne(tokens.get(0), notification, log);
            return;
        }
        sendMany(tokens, notification, log);
    }

    private void sendOne(PushToken token, Notification notification, NotificationLog log) {
        try {
            messaging.send(Message.builder()
                    .setToken(token.getToken())
                    .setNotification(notification)
                    .build(), properties.dryRunOrDefault());
        } catch (FirebaseMessagingException e) {
            dropIfDead(token, e.getMessagingErrorCode());
            throw new FcmException("푸시 발송 실패: event=" + log.getEventCode()
                    + ", code=" + e.getMessagingErrorCode(), e);
        }
    }

    /**
     * 기기가 여럿이면 한 번에 보낸다.
     *
     * <p>★ <b>하나라도 성공하면 성공으로 본다.</b> 폰은 받고 옛 태블릿 토큰만 죽은 경우가
     * 흔한데, 그걸 실패로 남기면 <b>사용자는 알림을 받았는데 이력은 실패</b>가 된다.
     */
    private void sendMany(List<PushToken> tokens, Notification notification,
                          NotificationLog log) {
        BatchResponse response;
        try {
            response = messaging.sendEachForMulticast(MulticastMessage.builder()
                    .addAllTokens(tokens.stream().map(PushToken::getToken).toList())
                    .setNotification(notification)
                    .build(), properties.dryRunOrDefault());
        } catch (FirebaseMessagingException e) {
            throw new FcmException("푸시 발송 실패: event=" + log.getEventCode(), e);
        }

        List<SendResponse> results = response.getResponses();
        for (int i = 0; i < results.size(); i++) {
            FirebaseMessagingException e = results.get(i).getException();
            if (e != null) {
                dropIfDead(tokens.get(i), e.getMessagingErrorCode());
            }
        }

        if (response.getSuccessCount() == 0) {
            throw new FcmException("기기 " + tokens.size() + "대 모두 실패했습니다: event="
                    + log.getEventCode());
        }
        if (response.getFailureCount() > 0) {
            log("일부 기기 발송 실패", log, response);
        }
    }

    /** ★ 죽은 토큰만 지운다. 일시적 오류({@code UNAVAILABLE} 등)로 지우면 멀쩡한 기기를 잃는다. */
    private void dropIfDead(PushToken token, MessagingErrorCode code) {
        if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
            token.markDeleted();
            log.info("죽은 푸시 토큰을 지웠다: tokenId={}, code={}", token.getId(), code);
        }
    }

    private void log(String message, NotificationLog entry, BatchResponse response) {
        log.warn("{}: event={}, 성공={}, 실패={}", message, entry.getEventCode(),
                response.getSuccessCount(), response.getFailureCount());
    }
}
