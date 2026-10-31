package com.dlab.domain.notification.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.notification.entity.NotificationChannel;
import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.entity.ReviewStatus;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import com.dlab.integration.aligo.AligoAlimtalkClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ★★ 알림톡 템플릿을 <b>알리고에 등록하고 검수를 요청</b>한다 (디랩 1031 2번).
 *
 * <h2>★ 왜 서버가 하는가 — 두 가지다</h2>
 * ① ⚠️ <b>검수가 4~5일 걸린다</b>(알리고 문서). 앱 스토어 제출이 <b>11/30</b> 이라
 * <b>리드타임이 곧 마감</b>이다.
 * ② ★ 알림톡은 <b>한 글자라도 다르면 발송이 실패한다.</b> 콘솔에 손으로 옮기면
 * <b>옮겨 적다 틀릴 자리</b>가 생기는데, 서버가 보내면 <b>DB 에 있는 문구가 그대로</b> 간다.
 *
 * <h2>⚠️ 기존 주석의 전제가 틀렸다</h2>
 * {@code AdminNotificationTemplateController} 가 <b>「카카오와 자동 연동 창구가 없어
 * 사람이 보고 넣는다」</b> 고 적고 있었다. ★ 알리고에 <b>등록·검수요청·목록조회 API 가
 * 있다</b> — 그래서 <b>코드를 손으로 받아 적는 단계와 상태를 눈으로 보고 넣는 단계가
 * 둘 다 사라진다.</b>
 *
 * <h2>★ 사람이 하는 일로 남는 것</h2>
 * <b>반려 사유를 읽고 문구를 고치는 것</b>뿐이다 — 그건 판단이라 자동화 대상이 아니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlimtalkTemplateRegistrar {

    private final NotificationTemplateRepository templateRepository;
    private final NotificationTemplateService templateService;
    /**
     * ★★ <b>{@code Optional} 로 받는다</b> — {@link AligoAlimtalkClient} 가
     * {@code @ConditionalOnExpression} 이라 <b>키가 없는 환경에서는 빈이 아예 없다.</b>
     *
     * <h2>⚠️ 필수 의존으로 두면 컨텍스트가 전면 실패한다</h2>
     * 실제로 그렇게 만들었다가 <b>테스트 1,789건이 깨졌다</b>(2026-10-31). 같은 모양을
     * FCM 에서도 한 번 겪었다 — CLAUDE.md 가 <b>「자격증명이 없으면 두 빈 모두
     * 만들어지지 않는다 … 한쪽만 걸었다가 로컬·CI 가 전부 깨졌다」</b> 고 적는다.
     *
     * <p>★ <b>이 서비스에 같은 조건을 거는 쪽은 답이 아니다</b> — 그러면 이것을
     * 주입받는 <b>컨트롤러까지 조건부로 만들어야</b> 하고, 조건이 전염된다.
     */
    private final Optional<AligoAlimtalkClient> aligoClient;

    /**
     * ★★ 등록 + 검수 요청을 <b>한 번에</b>.
     *
     * <h2>★ 문구가 확정되지 않으면 거부한다</h2>
     * ⚠️ 검수에 올린 뒤에는 <b>문구를 고치려면 처음부터</b>이고 4~5일이 또 걸린다.
     * 그래서 <b>{@code content_confirmed} 가 아닌 템플릿은 올리지 않는다</b> —
     * 지금 등록비 안내가 그 상태다(디랩 문구 회신 대기).
     *
     * <h2>⚠️ 테스트 모드와 무관하다</h2>
     * 등록은 발송이 아니라 <b>형상 관리</b>라 {@code testmode_yn} 이 없다 —
     * <b>테스트 모드를 켜 둬도 실제로 알리고에 등록된다.</b> 되돌리려면
     * 콘솔에서 지워야 한다.
     *
     * @return 알리고가 부여한 템플릿 코드. ★ {@code null} 이면 등록은 됐으나 코드를
     *         응답에서 찾지 못한 것이다 — {@link #syncReviewStatus} 로 확인한다
     */
    @Transactional
    public String registerAndRequestReview(Long templateId) {
        NotificationTemplate template = templateRepository.findById(templateId)
                .filter(t -> !t.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "알림 템플릿을 찾을 수 없습니다."));

        if (template.getChannel() != NotificationChannel.KAKAO_ALIMTALK) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "알림톡 템플릿만 카카오 검수 대상입니다.");
        }
        if (!template.isContentConfirmed()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "문구가 확정되지 않았습니다. 검수에 올린 뒤에는 문구를 고치려면 "
                            + "처음부터 다시 4~5일이 걸립니다.");
        }
        if (template.getReviewStatus() == ReviewStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 승인된 템플릿입니다. 문구를 바꾸려면 새 템플릿으로 올려 주세요.");
        }

        AligoAlimtalkClient client = requireClient();
        String name = templateName(template);
        String code = client.addTemplate(name, template.getBodyTemplate());

        if (code == null) {
            // ⚠️ 등록은 됐는데 코드를 모른다. ★ 여기서 예외를 던지면 ★ 알리고에는
            //    남아 있고 우리 DB 는 모르는 ★ 유령 템플릿이 된다 — 드러내고 넘긴다
            log.warn("⚠️ 템플릿 등록은 됐으나 코드를 받지 못했다: event={}. 목록 동기화로 확인할 것",
                    template.getEventCode());
            return null;
        }

        client.requestTemplateReview(code);
        // ★ 받은 코드를 그대로 박는다 — 사람이 옮겨 적는 단계가 사라진다
        templateService.submitForReview(templateId, code);

        log.info("★ 알림톡 검수 올림: event={}, tpl={} — ⚠️ 4~5일 소요",
                template.getEventCode(), code);
        return code;
    }

    /**
     * ★★ 알리고 목록을 읽어 <b>심사 상태를 되읽는다</b>.
     *
     * <p>★ 지금까지 <b>사람이 콘솔을 보고 넣어야</b> 했다. 목록 API 가 있으므로
     * <b>그 단계가 필요 없다.</b>
     *
     * <p>⚠️ <b>승인만 반영하고 반려는 사유를 함께 남긴다</b> — 반려는 사람이 읽고
     * 고쳐야 하는 일이라, 상태만 바꾸면 <b>왜 반려됐는지가 사라진다.</b>
     *
     * @return 상태가 바뀐 템플릿 수
     */
    @Transactional
    public int syncReviewStatus() {
        List<Map<String, Object>> rows = requireClient().listTemplates();
        if (rows.isEmpty()) {
            log.info("알리고 템플릿 목록이 비어 있다 — 등록된 것이 없거나 자격증명이 없다.");
            return 0;
        }

        int changed = 0;
        for (NotificationTemplate template : templateRepository.findAllByDeletedFalseOrderByEventCode()) {
            if (template.getKakaoTemplateCode() == null
                    || template.getReviewStatus() != ReviewStatus.SUBMITTED) {
                continue;
            }
            Optional<Map<String, Object>> row = rows.stream()
                    .filter(r -> template.getKakaoTemplateCode()
                            .equals(String.valueOf(r.get("templtCode"))))
                    .findFirst();
            if (row.isEmpty()) {
                continue;
            }
            // ★ 알리고가 쓰는 상태값. ⚠️ 문서에 목록이 없어 ★ 승인(A)만 확정으로 보고
            //    나머지는 ★ 건드리지 않는다 — 모르는 값을 반려로 읽으면 ★ 멀쩡한 템플릿이
            //    반려로 뒤집힌다
            String status = String.valueOf(row.get().get("inspStatus"));
            if ("APR".equalsIgnoreCase(status) || "A".equalsIgnoreCase(status)) {
                templateService.applyReviewResult(template.getId(), true,
                        "알리고 목록 동기화로 승인 확인");
                changed++;
            } else if ("REJ".equalsIgnoreCase(status) || "R".equalsIgnoreCase(status)) {
                templateService.applyReviewResult(template.getId(), false,
                        "알리고 목록 동기화로 반려 확인 — 사유는 알리고 콘솔에서 확인할 것");
                changed++;
            }
        }
        log.info("★ 알림톡 심사 상태 동기화: {}건 변경", changed);
        return changed;
    }

    /**
     * ★ 자격증명이 없으면 <b>사람이 읽을 오류</b>로 막는다.
     *
     * <p>⚠️ {@code NoSuchElementException} 으로 터지게 두면 화면에 <b>500 이 뜨고</b>
     * 원인이 「키가 없다」인지 알 수 없다.
     */
    private AligoAlimtalkClient requireClient() {
        return aligoClient.orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                "알림톡 자격증명(ALIGO_API_KEY)이 설정되지 않아 템플릿을 등록할 수 없습니다."));
    }

    /**
     * ★ 알리고 콘솔에서 사람이 찾는 이름.
     *
     * <p>★ 이벤트 코드를 그대로 쓴다 — <b>우리 DB 와 콘솔을 대조할 때 그게 유일한 키</b>다.
     * ⚠️ 한글 제목을 쓰면 <b>둘을 잇는 값이 없어진다.</b>
     */
    private String templateName(NotificationTemplate template) {
        return "DLAB_" + template.getEventCode().name();
    }
}
