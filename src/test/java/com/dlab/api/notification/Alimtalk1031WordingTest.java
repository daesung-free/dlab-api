package com.dlab.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.dlab.domain.notification.entity.NotificationEvent;
import com.dlab.domain.notification.entity.NotificationTemplate;
import com.dlab.domain.notification.repository.NotificationTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림톡 수정안(디랩 1031)의 <b>가드</b>.
 *
 * <h2>★ 왜 마이그레이션을 테스트하는가</h2>
 * 이 두 값은 <b>코드가 아니라 DB 행</b>이다. 그래서 누가 나중에 다른 마이그레이션으로
 * 되돌려도 <b>컴파일도 테스트도 깨지지 않는다</b> — 알림톡이 발송될 때서야
 * <b>카카오가 거절</b>하거나(문구 불일치), <b>형제 중 한 명의 안내가 사라진다.</b>
 *
 * <p>⚠️ 본문이 카카오 승인본과 <b>한 글자라도 다르면 발송이 실패한다.</b> 띄어쓰기도
 * 그대로여야 하므로 문자열을 그대로 비교한다.
 */
@SpringBootTest
@Transactional
class Alimtalk1031WordingTest {

    @Autowired NotificationTemplateRepository repository;

    /**
     * ★ 「승인해주세요」는 <b>띄어 쓰지 않는다</b> — 디랩 원문 그대로다.
     *
     * <p>⚠️ 「승인해 주세요」로 고치면 <b>맞춤법상 더 맞아 보여서</b> 누군가 바로잡고
     * 싶어진다. 그런데 카카오 승인본과 달라지면 <b>그 템플릿은 발송되지 않는다.</b>
     */
    @Test
    @DisplayName("★ 승인 요청 문구는 「승인해주세요」다 — 띄어 쓰면 카카오가 거절한다")
    void approvalRequestWording() {
        NotificationTemplate template = repository
                .findByEventCode(NotificationEvent.APPROVAL_REQUEST_CREATED)
                .orElseThrow();

        assertThat(template.getBodyTemplate())
                .contains("승인해주세요.")
                .doesNotContain("승인해 주세요")
                .doesNotContain("승인해 주십시오");
        // 첫 줄은 그대로 둔다
        assertThat(template.getBodyTemplate())
                .contains("{studentName} 학생이 {requestType}을 신청하였습니다.");
    }

    /**
     * ★★ 등록비 안내는 <b>학생별 발송</b>이다 — 형제가 있으면 따로 간다.
     *
     * <h2>⚠️ 묶으면 한 자녀의 안내가 사라진다</h2>
     * {@code student_specific = FALSE} 면 {@code ManualNotificationService} 가
     * <b>학부모당 한 통으로 중복을 제거한다.</b> 10/23 에는 디랩 문구에 학생명이 없어
     * 그것이 맞았지만, <b>10/31 수정안에 {@code #{학생명}} 이 다시 들어왔다.</b>
     *
     * <p>★ 이 값이 <b>「○명에게 발송됩니다」 숫자를 그대로 바꾼다</b> — 학부모 수에서
     * 학생 수로. 화면은 서버 숫자를 그대로 쓴다.
     */
    @Test
    @DisplayName("★★ 등록비 안내는 학생별 발송이다 — 묶으면 형제 중 한 명의 안내가 사라진다")
    void tuitionRegistrationIsPerStudent() {
        NotificationTemplate template = repository
                .findByEventCode(NotificationEvent.TUITION_REGISTRATION_NOTICE)
                .orElseThrow();

        assertThat(template.isStudentSpecific()).isTrue();
    }

    /**
     * ★ 급식 마감은 <b>그대로 공통 안내</b>다.
     *
     * <p>⚠️ 등록비와 같은 마이그레이션에서 FALSE 가 됐으므로 <b>같이 되돌리기 쉽다.</b>
     * 그 문구에는 학생명이 없고 <b>넣으면 문장이 어색해진다</b> — 마감일은 학생마다
     * 다르지 않다.
     */
    @Test
    @DisplayName("★ 급식 마감은 그대로 공통 안내다 — 등록비와 함께 되돌리지 않았다")
    void mealDeadlineStaysCommon() {
        NotificationTemplate template = repository
                .findByEventCode(NotificationEvent.MEAL_APPLY_DEADLINE)
                .orElseThrow();

        assertThat(template.isStudentSpecific()).isFalse();
    }

    /**
     * ★★ 학생별로 바꾸면서 <b>문구 확정을 함께 풀었다</b> — 그러지 않으면 모순이다.
     *
     * <h2>⚠️ 「확정 && 학생별 && 본문에 학생명 없음」은 거부된다</h2>
     * {@code NotificationTemplateService} 가 그 조합을 막는다. 지금 본문은
     * {@code '[DLab] {기간} {월} 등록기간입니다.'} 로 <b>학생명이 없다.</b> 확정을
     * 그대로 두면 <b>다음에 이 템플릿을 고치려는 순간 저장이 거부되고</b>,
     * 원인이 마이그레이션에 있어 찾기 어렵다.
     *
     * <p>★ <b>잃는 것은 없다</b> — 알림톡은 심사 통과가 있어야 나가고 지금
     * {@code review_status} 가 {@code DRAFT} 다(카카오 반려분).
     */
    @Test
    @DisplayName("★★ 등록비는 문구 확정이 풀려 있다 — 학생명 없는 본문으로 확정이면 수정이 거부된다")
    void tuitionRegistrationContentIsUnconfirmed() {
        NotificationTemplate template = repository
                .findByEventCode(NotificationEvent.TUITION_REGISTRATION_NOTICE)
                .orElseThrow();

        if (template.getBodyTemplate().contains("{studentName}")) {
            // 디랩 문구가 들어온 뒤라면 확정돼 있어도 모순이 아니다
            return;
        }
        assertThat(template.isContentConfirmed())
                .as("★ 본문에 {studentName} 이 없는 동안은 확정하지 않는다")
                .isFalse();
        assertThat(template.isSendable())
                .as("⚠️ 발송은 어차피 막혀 있다 — 심사 통과가 없다")
                .isFalse();
    }
}
