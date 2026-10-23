package com.dlab.domain.share.service;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.admission.entity.AdmissionReservation;
import com.dlab.domain.admission.entity.AdmissionReservationStatusLog;
import com.dlab.domain.admission.entity.ConsultStatus;
import com.dlab.domain.admission.entity.StatusActorType;
import com.dlab.domain.admission.entity.WaitlistAnswer;
import com.dlab.domain.admission.repository.AdmissionReservationRepository;
import com.dlab.domain.admission.repository.AdmissionReservationStatusLogRepository;
import com.dlab.domain.admission.service.AdmissionReservationAdminService;
import com.dlab.domain.share.entity.PublicShareToken;
import com.dlab.domain.share.entity.ShareTargetType;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대기자 응답 — 「대기자 안내」 알림톡 버튼이 여는 페이지.
 *
 * <p>알림톡은 단방향이다. 학생이 답장하면 카카오톡 1:1 채팅으로 들어가고 우리 시스템과
 * 연결되지 않아, <b>지금은 전화를 받아 직원이 손으로 적는다.</b> 그 수기 입력을 없애려고
 * 등록 의사를 버튼으로 받는다.
 *
 * <h2>★★ 답변은 1회 고정이다</h2>
 * 두 번째 응답은 거부한다 — 클라이언트 결정이다(<i>"한 번 누르면 아예 수정 안 되게.
 * 이걸로 장난칠 수도 있잖아. 진짜로 수정해야 되는 거면 전화하겠지"</i>).
 *
 * <p>⚠️ <b>그래도 토큰을 1회용으로 죽이지 않는다.</b> 페이지는 계속 열려야 하고
 * {@code answered}·{@code answeredAt} 으로 <b>자기가 무엇을 눌렀는지 보여야 한다.</b>
 * 안 그러면 「눌렀는데 아무 일도 없었다」로 다시 열려 하고, 그때 페이지가 안 열려
 * 전화가 온다 — 없애려던 전화가 그대로 돌아온다.
 *
 * <h2>★ 상태를 바로 바꾸고 「학생 응답」으로 남긴다</h2>
 * 직원이 관리자 웹에서 덮을 수 있고, 그 변경은 「직원 변경」으로 구분돼 남는다
 * ({@link StatusActorType}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicWaitlistShareService {

    /** 열 수 없을 때. <b>없음·만료를 가르지 않는다</b> — 가르면 토큰 대입에 힌트가 된다. */
    public static final String UNAVAILABLE_MESSAGE =
            """
            등록 안내를 확인할 수 없습니다.
            유효하지 않은 링크이거나, 응답 기간이 지났습니다.
            자세한 내용은 학원으로 문의해 주시기 바랍니다.""";

    /** 두 번째 응답. <b>문구가 「전화해 달라」로 끝나야 한다</b> — 수정 경로가 그것뿐이다. */
    public static final String ALREADY_ANSWERED_MESSAGE =
            "이미 응답하셨습니다. 변경이 필요하시면 학원으로 연락해 주십시오.";

    public static final String ACCEPTED_MESSAGE = "응답이 접수되었습니다. 감사합니다.";

    private final PublicShareTokenService tokenService;
    private final AdmissionReservationRepository reservationRepository;
    private final AdmissionReservationStatusLogRepository statusLogRepository;
    private final AdmissionReservationAdminService adminService;
    private final Clock clock;

    /**
     * 공유 링크 발급 (관리자).
     *
     * <p>★ <b>권한 검사는 기존 조회 경로에 맡긴다</b> — 지점 범위 판정을 여기서 다시 짜면
     * 한쪽만 고쳐질 수 있고, 남의 지점 대기자로 공개 링크를 만들 수 있게 된다.
     *
     * <p>⚠️ <b>기한을 인자로 받지 않는다</b>(설계 결정 5). 서버 기본값
     * ({@link PublicShareTokenService#WAITLIST_TTL})을 쓴다.
     */
    @Transactional
    public PublicShareToken share(AuthPrincipal me, Long reservationId) {
        AdmissionReservation reservation = adminService.get(me, reservationId);
        Instant deadline = Instant.now(clock).plus(PublicShareTokenService.WAITLIST_TTL);
        return tokenService.issueOrReuse(ShareTargetType.ADMISSION_RESERVATION,
                reservation.getId(), reservation.getAcademy(), reservation.getYear(), deadline,
                // ★ 기한은 처음 정한 것을 그대로 둔다. 누를 때마다 7일이 다시 붙으면
                //   기한이 사실상 없어지고, 화면이 보여준 날짜가 조용히 바뀐다
                PublicShareTokenService.ExpiryOnReuse.KEEP);
    }

    /** 공개 조회. */
    @Transactional(readOnly = true)
    public View view(String token) {
        Optional<PublicShareToken> share =
                tokenService.resolve(token, ShareTargetType.ADMISSION_RESERVATION);
        if (share.isEmpty()) {
            return View.unavailable();
        }
        Optional<AdmissionReservation> found = reservation(share.get());
        if (found.isEmpty()) {
            return View.unavailable();
        }
        AdmissionReservation reservation = found.get();
        AdmissionReservationStatusLog answered = studentAnswer(reservation.getId());

        return new View(true, reservation.getStudentName(),
                reservation.getAcademy().getAcadNm(),
                share.get().getExpiresAt(),
                answered == null ? null : WaitlistAnswer.fromStatus(answered.getToStatus()),
                answered == null ? null : answered.getCreatedAt(),
                null);
    }

    /**
     * 응답.
     *
     * <p>★★ <b>두 번째는 거부한다.</b> 사전 조회만으로는 막히지 않는다 — 버튼을 두 번
     * 빠르게 누르면 두 요청이 모두 「아직 응답 없음」을 읽고 둘 다 통과한다. 그래서
     * <b>DB 부분 유니크 제약</b>({@code uq_admission_status_log_student_answer})이 최종
     * 방어선이고, 여기서 그 위반을 같은 응답으로 바꿔 돌려준다.
     *
     * @param requesterIp 공개 경로라 주체가 없어 IP 를 남긴다
     */
    @Transactional
    public AnswerResult answer(String token, WaitlistAnswer answer, String requesterIp) {
        Optional<PublicShareToken> share =
                tokenService.resolve(token, ShareTargetType.ADMISSION_RESERVATION);
        if (share.isEmpty()) {
            return AnswerResult.rejected(UNAVAILABLE_MESSAGE);
        }
        Optional<AdmissionReservation> found = reservation(share.get());
        if (found.isEmpty()) {
            return AnswerResult.rejected(UNAVAILABLE_MESSAGE);
        }
        AdmissionReservation reservation = found.get();

        if (studentAnswer(reservation.getId()) != null) {
            return AnswerResult.rejected(ALREADY_ANSWERED_MESSAGE);
        }

        ConsultStatus from = reservation.getConsultStatus();
        try {
            statusLogRepository.saveAndFlush(new AdmissionReservationStatusLog(
                    reservation, from, answer.status(),
                    // 이력만 보고도 경로를 알 수 있어야 한다 — 「직원이 전화로 들었다」와 다르다
                    "대기자 안내 링크 응답(" + answer.name() + ")",
                    StatusActorType.STUDENT, requesterIp));
        } catch (DataIntegrityViolationException e) {
            // 동시에 두 번 눌렀다. 제약이 막아준 것이라 같은 거절 응답으로 돌려준다
            log.info("대기자 응답 중복: reservationId={}", reservation.getId());
            return AnswerResult.rejected(ALREADY_ANSWERED_MESSAGE);
        }

        // ★ 이력을 먼저 쓰고 상태를 바꾼다. 제약 위반이면 상태가 바뀌지 않아야 한다 —
        //   순서를 뒤집으면 거부된 응답이 상태만 바꿔 놓는다
        reservation.changeStatus(answer.status());
        log.info("대기자 응답 접수: reservationId={}, answer={}", reservation.getId(), answer);
        return AnswerResult.ok();
    }

    private Optional<AdmissionReservation> reservation(PublicShareToken share) {
        return reservationRepository.findById(share.getTargetId())
                .filter(r -> !r.isDeleted());
    }

    /** 학생 응답 1건. 제약 때문에 실제로는 0건 또는 1건이다. */
    private AdmissionReservationStatusLog studentAnswer(Long reservationId) {
        List<AdmissionReservationStatusLog> answers =
                statusLogRepository.findStudentAnswers(reservationId);
        return answers.isEmpty() ? null : answers.get(0);
    }

    /**
     * @param deadline 응답 기한. 토큰의 만료 시각이다
     * @param answered 아직 안 눌렀으면 {@code null}
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "PublicWaitlistView")
    public record View(boolean available, String studentName, String academyName,
                       Instant deadline, WaitlistAnswer answered, Instant answeredAt,
                       String message) {

        static View unavailable() {
            return new View(false, null, null, null, null, null, UNAVAILABLE_MESSAGE);
        }
    }

    /** @param accepted {@code false} 면 이미 응답했거나 기한이 지났다 */
    public record AnswerResult(boolean accepted, String message) {

        /** 이름이 {@code accepted} 면 record 접근자와 겹쳐 컴파일되지 않는다. */
        static AnswerResult ok() {
            return new AnswerResult(true, ACCEPTED_MESSAGE);
        }

        static AnswerResult rejected(String message) {
            return new AnswerResult(false, message);
        }
    }
}
