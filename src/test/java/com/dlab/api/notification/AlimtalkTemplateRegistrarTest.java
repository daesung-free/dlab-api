package com.dlab.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dlab.common.exception.BusinessException;
import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.ReviewStatus;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import com.dlab.domain.notification.service.AlimtalkTemplateRegistrar;
import com.dlab.integration.aligo.AligoAlimtalkClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★★ 알림톡 템플릿 <b>등록·검수 요청</b> (디랩 1031 2번).
 *
 * <p>★ 알리고 클라이언트는 <b>목</b>이다 — ⚠️ 실제로 부르면 <b>진짜 등록된다</b>
 * (등록은 발송이 아니라 형상 관리라 <b>테스트 모드가 없다</b>).
 */
@SpringBootTest
@Transactional
class AlimtalkTemplateRegistrarTest {

    @Autowired AlimtalkTemplateRegistrar registrar;
    @Autowired NotificationTemplateRepository templateRepository;
    @Autowired EntityManager em;

    @MockitoBean AligoAlimtalkClient aligoClient;

    /**
     * ★★ <b>문구가 확정되지 않으면 거부한다.</b>
     *
     * <p>⚠️ 검수에 올린 뒤에는 <b>문구를 고치려면 처음부터</b>이고 <b>4~5일이 또
     * 걸린다.</b> ★ 지금 <b>등록비 안내가 그 상태</b>다(디랩 문구 회신 대기) —
     * 그래서 이 가드가 실제로 막는 대상이 있다.
     */
    @Test
    @DisplayName("★★ 문구 미확정은 거부한다 — 올린 뒤 고치면 4~5일이 또 걸린다")
    void rejectsUnconfirmedContent() {
        var template = templateRepository
                .findByEventCode(NotificationEvent.TUITION_REGISTRATION_NOTICE)
                .orElseThrow();
        assertThat(template.isContentConfirmed())
                .as("★ 등록비 안내는 문구 회신 대기라 확정이 풀려 있다")
                .isFalse();

        assertThatThrownBy(() -> registrar.registerAndRequestReview(template.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("4~5일");

        verify(aligoClient, never()).addTemplate(any(), any());
    }

    /**
     * ★★ 등록하면 <b>검수 요청까지 하고 받은 코드를 박는다.</b>
     *
     * <p>★ 사람이 <b>코드를 받아 적는 단계가 사라진다</b> — ⚠️ 알림톡은 한 글자라도
     * 다르면 발송이 실패하므로 손으로 옮기는 자리를 없애는 것이 목적이다.
     */
    @Test
    @DisplayName("★★ 등록하면 검수 요청까지 하고 받은 코드를 박는다 — 손으로 옮기지 않는다")
    void registersAndRequestsReview() {
        var template = templateRepository
                .findByEventCode(NotificationEvent.APPROVAL_REQUEST_CREATED)
                .orElseThrow();
        when(aligoClient.addTemplate(any(), any())).thenReturn("UM_9999");

        String code = registrar.registerAndRequestReview(template.getId());
        em.flush();
        em.clear();

        assertThat(code).isEqualTo("UM_9999");
        verify(aligoClient).requestTemplateReview("UM_9999");

        var saved = templateRepository.findById(template.getId()).orElseThrow();
        assertThat(saved.getKakaoTemplateCode()).isEqualTo("UM_9999");
        assertThat(saved.getReviewStatus()).isEqualTo(ReviewStatus.SUBMITTED);
    }

    /**
     * ★★ 이름에 <b>이벤트 코드</b>를 쓴다.
     *
     * <p>★ 우리 DB 와 알리고 콘솔을 <b>대조할 때 그게 유일한 키</b>다 —
     * ⚠️ 한글 제목을 쓰면 <b>둘을 잇는 값이 없어진다.</b>
     */
    @Test
    @DisplayName("★★ 템플릿 이름에 이벤트 코드를 쓴다 — 콘솔과 대조할 유일한 키다")
    void nameCarriesEventCode() {
        var template = templateRepository
                .findByEventCode(NotificationEvent.APPROVAL_REQUEST_CREATED)
                .orElseThrow();
        when(aligoClient.addTemplate(any(), any())).thenReturn("UM_8888");

        registrar.registerAndRequestReview(template.getId());

        verify(aligoClient).addTemplate(
                eq("DLAB_APPROVAL_REQUEST_CREATED"), eq(template.getBodyTemplate()));
    }

    /**
     * ★★ 코드를 못 받아도 <b>예외를 던지지 않는다.</b>
     *
     * <p>⚠️ 여기서 던지면 <b>알리고에는 남아 있고 우리 DB 는 모르는 유령 템플릿</b>이
     * 된다 — 드러내고 넘겨 <b>동기화로 확인</b>하게 한다.
     */
    @Test
    @DisplayName("★★ 코드를 못 받아도 던지지 않는다 — 던지면 유령 템플릿이 남는다")
    void missingCodeIsNotAnException() {
        var template = templateRepository
                .findByEventCode(NotificationEvent.APPROVAL_REQUEST_CREATED)
                .orElseThrow();
        when(aligoClient.addTemplate(any(), any())).thenReturn(null);

        String code = registrar.registerAndRequestReview(template.getId());

        assertThat(code).isNull();
        // ★ 코드가 없으니 검수 요청도 못 한다 — 동기화로 확인해야 한다
        verify(aligoClient, never()).requestTemplateReview(any());
    }

    /** ★ 알림톡이 아닌 채널은 대상이 아니다. */
    @Test
    @DisplayName("★ FCM 템플릿은 카카오 검수 대상이 아니다")
    void onlyAlimtalkIsReviewed() {
        var fcm = templateRepository
                .findByEventCode(NotificationEvent.APPROVER_PREFERENCE_CHANGED)
                .orElseThrow();

        assertThatThrownBy(() -> registrar.registerAndRequestReview(fcm.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("알림톡");
    }
}
