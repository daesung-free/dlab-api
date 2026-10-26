package com.dlab.integration.fcm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.dlab.domain.appconfig.entity.Platform;
import com.dlab.domain.appconfig.entity.PushToken;
import com.dlab.domain.appconfig.repository.PushTokenRepository;
import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationLog;
import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.entity.RecipientType;
import com.dlab.domain.user.entity.Account;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * FCM 푸시 발송기.
 *
 * <p>지키려는 것 — <b>안 보낸 것이 성공으로 남지 않을 것</b>,
 * <b>죽은 토큰이 영구히 실패를 쌓지 않을 것</b>,
 * <b>멀쩡한 기기를 일시적 오류로 잃지 않을 것</b>.
 */
class FcmPushSenderTest {

    private static final long ACCOUNT_ID = 42L;

    private final FirebaseMessaging messaging = mock(FirebaseMessaging.class);
    private final PushTokenRepository tokenRepository = mock(PushTokenRepository.class);
    private final FcmProperties properties = new FcmProperties("/dev/null", false);
    private final FcmPushSender sender =
            new FcmPushSender(messaging, tokenRepository, properties);

    @Test
    @DisplayName("채널은 FCM_PUSH 다 — 채널이 발송기를 고르는 키다")
    void 채널이_키다() {
        assertThat(sender.channel()).isEqualTo(NotificationChannel.FCM_PUSH);
    }

    @Test
    @DisplayName("★★ 계정 없는 수신자는 푸시 대상이 아니다 — 급식업체·대기자는 번호만 있다")
    void 계정이_없으면_던진다() {
        NotificationLog log = NotificationLog.toPhone(null, (short) 2026,
                NotificationEvent.MISSING_ATTENDANCE, NotificationChannel.FCM_PUSH,
                "010-0000-0001", "업체", null, "제목", "본문", Map.of(), "dedup-1");

        assertThatThrownBy(() -> sender.send(log, template()))
                .isInstanceOf(FcmException.class)
                .hasMessageContaining("계정이 있어야");
    }

    @Test
    @DisplayName("★★ 등록된 기기가 없으면 실패로 남긴다 — 성공으로 두면 안 간 사실이 사라진다")
    void 기기가_없으면_던진다() {
        given(tokenRepository.findByAccountIdAndDeletedFalse(ACCOUNT_ID))
                .willReturn(List.of());

        assertThatThrownBy(() -> sender.send(toAccount(), template()))
                .isInstanceOf(FcmException.class)
                .hasMessageContaining("등록된 기기가 없습니다");
    }

    @Test
    @DisplayName("★★ 죽은 토큰(UNREGISTERED)은 지운다 — 안 지우면 그 계정 발송이 영구히 실패한다")
    void 죽은_토큰을_지운다() throws Exception {
        PushToken token = token(1L, "dead-token");
        given(tokenRepository.findByAccountIdAndDeletedFalse(ACCOUNT_ID))
                .willReturn(List.of(token));
        FirebaseMessagingException dead = messagingException(MessagingErrorCode.UNREGISTERED);
        given(messaging.send(any(), anyBoolean())).willThrow(dead);

        assertThatThrownBy(() -> sender.send(toAccount(), template()))
                .isInstanceOf(FcmException.class);

        assertThat(token.isDeleted())
                .as("다음 발송에서 다시 실패하지 않도록 지운다")
                .isTrue();
    }

    @Test
    @DisplayName("⚠️ 일시적 오류로는 토큰을 지우지 않는다 — 멀쩡한 기기를 잃는다")
    void 일시적_오류는_살려둔다() throws Exception {
        PushToken token = token(2L, "live-token");
        given(tokenRepository.findByAccountIdAndDeletedFalse(ACCOUNT_ID))
                .willReturn(List.of(token));
        FirebaseMessagingException temporary =
                messagingException(MessagingErrorCode.UNAVAILABLE);
        given(messaging.send(any(), anyBoolean())).willThrow(temporary);

        assertThatThrownBy(() -> sender.send(toAccount(), template()))
                .isInstanceOf(FcmException.class);

        assertThat(token.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("★ 기기 여럿 중 하나라도 성공하면 성공이다 — 받았는데 실패로 남으면 안 된다")
    void 하나라도_성공하면_성공이다() throws Exception {
        PushToken live = token(3L, "phone");
        PushToken dead = token(4L, "old-tablet");
        given(tokenRepository.findByAccountIdAndDeletedFalse(ACCOUNT_ID))
                .willReturn(List.of(live, dead));
        BatchResponse result = batch(success(), failure(MessagingErrorCode.UNREGISTERED));
        given(messaging.sendEachForMulticast(any(MulticastMessage.class), anyBoolean()))
                .willReturn(result);

        sender.send(toAccount(), template());   // 던지지 않는다

        assertThat(dead.isDeleted()).as("죽은 쪽만 지운다").isTrue();
        assertThat(live.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("기기 전부 실패하면 던진다 — 아무에게도 안 갔다")
    void 전부_실패하면_던진다() throws Exception {
        given(tokenRepository.findByAccountIdAndDeletedFalse(ACCOUNT_ID))
                .willReturn(List.of(token(5L, "a"), token(6L, "b")));
        BatchResponse result = batch(failure(MessagingErrorCode.UNREGISTERED),
                failure(MessagingErrorCode.UNREGISTERED));
        given(messaging.sendEachForMulticast(any(MulticastMessage.class), anyBoolean()))
                .willReturn(result);

        assertThatThrownBy(() -> sender.send(toAccount(), template()))
                .isInstanceOf(FcmException.class)
                .hasMessageContaining("모두 실패");
    }

    @Test
    @DisplayName("기기가 하나면 묶음 발송을 쓰지 않는다")
    void 한_대면_단건으로_보낸다() throws Exception {
        given(tokenRepository.findByAccountIdAndDeletedFalse(ACCOUNT_ID))
                .willReturn(List.of(token(7L, "only")));

        sender.send(toAccount(), template());

        verify(messaging).send(any(), anyBoolean());
        verify(messaging, never()).sendEachForMulticast(any(), anyBoolean());
    }

    // ── 거푸집 ────────────────────────────────────────────────

    private NotificationTemplate template() {
        return new NotificationTemplate(NotificationEvent.MISSING_ATTENDANCE,
                NotificationChannel.FCM_PUSH, RecipientType.PARENT, "studentName");
    }

    private NotificationLog toAccount() {
        Account account = mock(Account.class);
        given(account.getId()).willReturn(ACCOUNT_ID);
        return new NotificationLog(null, (short) 2026, NotificationEvent.MISSING_ATTENDANCE,
                NotificationChannel.FCM_PUSH, account, null,
                "제목", "본문", Map.of(), "dedup-" + System.nanoTime());
    }

    private PushToken token(Long id, String value) {
        PushToken token = new PushToken(mock(Account.class), value,
                Platform.ANDROID, Instant.now());
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    private FirebaseMessagingException messagingException(MessagingErrorCode code) {
        FirebaseMessagingException e = mock(FirebaseMessagingException.class);
        given(e.getMessagingErrorCode()).willReturn(code);
        return e;
    }

    private SendResponse success() {
        // ⚠️ getException() 기본값이 null 이라 스터빙하지 않는다 — 하면 중첩 스터빙이 된다
        return mock(SendResponse.class);
    }

    private SendResponse failure(MessagingErrorCode code) {
        FirebaseMessagingException cause = messagingException(code);
        SendResponse response = mock(SendResponse.class);
        given(response.getException()).willReturn(cause);
        return response;
    }

    private BatchResponse batch(SendResponse... responses) {
        BatchResponse batch = mock(BatchResponse.class);
        given(batch.getResponses()).willReturn(List.of(responses));
        long failures = List.of(responses).stream()
                .filter(r -> r.getException() != null).count();
        given(batch.getSuccessCount()).willReturn((int) (responses.length - failures));
        given(batch.getFailureCount()).willReturn((int) failures);
        return batch;
    }
}
