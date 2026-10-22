package com.dlab.api.pub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.admission.entity.AdmissionReservation;
import com.dlab.domain.admission.entity.ConsultStatus;
import com.dlab.domain.admission.entity.StatusActorType;
import com.dlab.domain.admission.entity.WaitlistAnswer;
import com.dlab.domain.admission.repository.AdmissionReservationStatusLogRepository;
import com.dlab.domain.admission.service.AdmissionReservationAdminService;
import com.dlab.domain.file.entity.FileAttachment;
import com.dlab.domain.file.entity.FileVisibility;
import com.dlab.domain.file.repository.FileAttachmentRepository;
import com.dlab.domain.file.service.FileStorage;
import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.notice.entity.NoticeAuthorType;
import com.dlab.domain.notice.service.NoticeAttachments;
import com.dlab.domain.share.service.PublicNoticeShareService;
import com.dlab.domain.share.service.PublicWaitlistShareService;
import com.dlab.domain.user.entity.Academy;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 공개 공유 페이지 — 안내문·대기자 응답.
 *
 * <p>지키려는 것 여섯이다.
 * <ol>
 *   <li><b>★★ 없는 토큰·만료 토큰도 200 + {@code available:false}</b> — 404 로 내리면
 *       토큰을 두드려 보는 쪽에 「이건 있다/없다」 힌트가 된다</li>
 *   <li><b>★★ 답변은 1회 고정</b> — 두 번째는 「전화해 달라」로 거부한다</li>
 *   <li><b>★ 그래도 페이지는 계속 열린다</b> — 자기가 무엇을 눌렀는지 보여야 한다.
 *       안 보이면 다시 열려 하고, 그때 안 열려 없애려던 전화가 돌아온다</li>
 *   <li><b>★ 학생 응답이 상태를 바로 바꾸고 「학생 응답」으로 남는다</b> — 직원이 덮으면
 *       그 변경은 「직원 변경」으로 구분돼 남는다</li>
 *   <li><b>★★ 남의 첨부는 받을 수 없다</b> — {@code fileId} 만 바꿔 열리면 공개 토큰
 *       하나로 성적표·재학증명서까지 나간다</li>
 *   <li><b>★ 기한은 서버가 정한다</b> — 발급 요청에서 받지 않는다</li>
 * </ol>
 */
@SpringBootTest
@Transactional
class PublicShareFlowTest {

    @Autowired PublicNoticeShareService noticeShare;
    @Autowired PublicWaitlistShareService waitlistShare;
    @Autowired AdmissionReservationAdminService reservationAdmin;
    @Autowired AdmissionReservationStatusLogRepository statusLogRepository;
    @Autowired FileAttachmentRepository fileRepository;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    /** S3 가 없어도 돈다 — 우리가 보는 것은 <b>어느 파일을 열어주는가</b>다. */
    @MockitoBean FileStorage storage;

    static final short YEAR = 2094;

    Academy academy;
    AuthPrincipal admin;
    Notice notice;
    AdmissionReservation reservation;

    @BeforeEach
    void setUp() {
        academy = new Academy("95", "공유지점", LocalTime.of(9, 0));
        em.persist(academy);

        notice = Notice.ofBranch(academy, YEAR, "등록비 안내", "등록비는 75만원입니다.",
                NoticeAuthorType.EMPLOYEE, 1L);
        em.persist(notice);

        reservation = AdmissionReservation.builder()
                .academy(academy).year(YEAR).rsvCd("RSV-2094-0001")
                .studentName("박대기").studentTel("010-1111-2222").parentTel("010-3333-4444")
                .gender("F").birth("20070101").stdGrade("N")
                .agreeAd(true).promoAd(false)
                .build();
        em.persist(reservation);
        em.flush();

        admin = new AuthPrincipal(1L, "admin", academy.getId(),
                Set.of(Role.SUPER_ADMIN), true, false);

        Mockito.when(storage.publicUrl(Mockito.anyString()))
                .thenReturn("https://cdn.example.test/file");
    }

    // ───────────────────────── 안내문 ─────────────────────────

    @Test
    @DisplayName("안내문 — 공유 토큰으로 로그인 없이 열린다")
    void noticeOpensByToken() {
        String token = noticeShare.share(admin, notice.getId()).getToken();

        var view = noticeShare.view(token);

        assertThat(view.available()).isTrue();
        assertThat(view.title()).isEqualTo("등록비 안내");
        assertThat(view.content()).contains("75만원");
        assertThat(view.academyName()).isEqualTo("공유지점");
        assertThat(view.postedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★ 없는 토큰도 200 + available:false — 404 면 「이건 있다」가 새어 나간다")
    void unknownNoticeTokenIsNotFound() {
        var view = noticeShare.view("존재하지않는토큰");

        assertThat(view.available()).isFalse();
        assertThat(view.message()).isEqualTo(PublicNoticeShareService.UNAVAILABLE_MESSAGE);
        assertThat(view.title()).isNull();
    }

    @Test
    @DisplayName("★ 게시가 끝난 공지는 링크로도 열리지 않는다 — 안 그러면 「내렸는데 보인다」가 된다")
    void expiredNoticeIsUnavailable() {
        String token = noticeShare.share(admin, notice.getId()).getToken();
        notice.update(notice.getTitle(), notice.getContent(), false, false, null,
                Instant.now().minus(1, ChronoUnit.DAYS));
        em.flush();

        assertThat(noticeShare.view(token).available()).isFalse();
    }

    @Test
    @DisplayName("★ 기한은 공지의 게시 종료를 따른다 — 요청에서 받지 않는다")
    void expiresAtComesFromNotice() {
        Instant end = Instant.now().plus(10, ChronoUnit.DAYS);
        notice.update(notice.getTitle(), notice.getContent(), false, false, null, end);
        em.flush();

        assertThat(noticeShare.share(admin, notice.getId()).getExpiresAt()).isEqualTo(end);
    }

    @Test
    @DisplayName("★★ 남의 첨부는 받을 수 없다 — fileId 만 바꿔 열리면 성적표까지 나간다")
    void foreignAttachmentIsRejected() {
        FileAttachment mine = fileRepository.save(new FileAttachment(
                FileVisibility.PUBLIC, "notice/2094/1/a.png", "안내문.png", "image/png", 10,
                NoticeAttachments.OWNER_TYPE, notice.getId()));
        // 같은 범용 테이블에 들어 있는 남의 서류
        FileAttachment others = fileRepository.save(new FileAttachment(
                FileVisibility.PRIVATE, "profile/2094/9/b.pdf", "성적표.pdf",
                "application/pdf", 10, "STUDENT_PROFILE", 9L));
        em.flush();

        String token = noticeShare.share(admin, notice.getId()).getToken();

        assertThat(noticeShare.fileUrl(token, mine.getId())).isNotBlank();
        assertThatThrownBy(() -> noticeShare.fileUrl(token, others.getId()))
                .isInstanceOf(BusinessException.class)
                // 「없는 파일」과 같은 문구다 — 갈라서 내리면 id 를 세어 존재를 알아낼 수 있다
                .hasMessageContaining("파일을 찾을 수 없습니다");
    }

    // ───────────────────────── 대기자 ─────────────────────────

    @Test
    @DisplayName("대기자 — 기한은 서버 기본값(7일)이다")
    void waitlistDeadlineIsServerSide() {
        var view = waitlistShare.view(waitlistShare.share(admin, reservation.getId()).getToken());

        assertThat(view.available()).isTrue();
        assertThat(view.studentName()).isEqualTo("박대기");
        assertThat(view.deadline()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
        assertThat(view.answered()).isNull();
        assertThat(view.answeredAt()).isNull();
    }

    @Test
    @DisplayName("★ 학생 응답이 상태를 바로 바꾸고 「학생 응답」으로 남는다")
    void answerChangesStatusAndLogsStudent() {
        String token = waitlistShare.share(admin, reservation.getId()).getToken();

        var result = waitlistShare.answer(token, WaitlistAnswer.ACCEPT, "10.0.0.9");

        assertThat(result.accepted()).isTrue();
        assertThat(reservation.getConsultStatus()).isEqualTo(ConsultStatus.CONFIRMED);

        var logs = statusLogRepository.findStudentAnswers(reservation.getId());
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getActorType()).isEqualTo(StatusActorType.STUDENT);
        assertThat(logs.get(0).getActorIp()).isEqualTo("10.0.0.9");
        assertThat(logs.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★ 두 번째 응답은 거부한다 — 변경은 전화로만")
    void secondAnswerIsRejected() {
        String token = waitlistShare.share(admin, reservation.getId()).getToken();
        waitlistShare.answer(token, WaitlistAnswer.ACCEPT, "10.0.0.9");

        var again = waitlistShare.answer(token, WaitlistAnswer.DECLINE, "10.0.0.9");

        assertThat(again.accepted()).isFalse();
        assertThat(again.message())
                .isEqualTo(PublicWaitlistShareService.ALREADY_ANSWERED_MESSAGE);
        // 거부된 응답이 상태를 바꿔 놓지 않았다
        assertThat(reservation.getConsultStatus()).isEqualTo(ConsultStatus.CONFIRMED);
        assertThat(statusLogRepository.findStudentAnswers(reservation.getId())).hasSize(1);
    }

    @Test
    @DisplayName("★ 응답 뒤에도 페이지는 열리고 자기가 뭘 눌렀는지 보인다 — 안 보이면 전화가 온다")
    void pageStaysOpenAfterAnswer() {
        String token = waitlistShare.share(admin, reservation.getId()).getToken();
        waitlistShare.answer(token, WaitlistAnswer.DECLINE, "10.0.0.9");

        var view = waitlistShare.view(token);

        assertThat(view.available()).isTrue();
        assertThat(view.answered()).isEqualTo(WaitlistAnswer.DECLINE);
        assertThat(view.answeredAt()).isNotNull();
    }

    @Test
    @DisplayName("★ 직원이 덮은 변경은 「직원 변경」으로 남고 학생이 누른 값은 그대로다")
    void staffOverrideIsDistinguishable() {
        String token = waitlistShare.share(admin, reservation.getId()).getToken();
        waitlistShare.answer(token, WaitlistAnswer.ACCEPT, "10.0.0.9");

        reservationAdmin.changeStatus(admin, reservation.getId(),
                ConsultStatus.NOT_REGISTERED, "전화로 취소 요청");
        em.flush();

        assertThat(reservation.getConsultStatus()).isEqualTo(ConsultStatus.NOT_REGISTERED);
        // 학생 응답 이력은 바뀌지 않는다 — 「무엇을 눌렀는가」가 그대로 남는다
        assertThat(waitlistShare.view(token).answered()).isEqualTo(WaitlistAnswer.ACCEPT);
        assertThat(statusLogRepository.findByReservationId(reservation.getId()))
                .extracting(l -> l.getActorType())
                .containsExactlyInAnyOrder(StatusActorType.STAFF, StatusActorType.STUDENT);
    }

    @Test
    @DisplayName("★★ 없는 토큰으로 응답해도 200 + accepted:false 다")
    void unknownWaitlistTokenAnswer() {
        var result = waitlistShare.answer("없는토큰", WaitlistAnswer.ACCEPT, "10.0.0.9");

        assertThat(result.accepted()).isFalse();
        assertThat(result.message()).isEqualTo(PublicWaitlistShareService.UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("★ 종류가 다른 토큰은 통하지 않는다 — targetId 가 테이블마다 따로 세어 우연히 맞는다")
    void tokenKindIsChecked() {
        String noticeToken = noticeShare.share(admin, notice.getId()).getToken();

        assertThat(waitlistShare.view(noticeToken).available()).isFalse();
    }
}
