package com.dlab.api.admin.admission;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.admission.entity.*;
import com.dlab.domain.admission.service.AdmissionDuplicateService;
import com.dlab.domain.admission.service.AdmissionReservationAdminService;
import com.dlab.domain.user.entity.GradeType;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 입학예약 관리 = 대기자 관리 (F-4.2-1).
 *
 * <h2>신청은 이미 들어온다</h2>
 * 홈페이지 수신({@code POST /dlab/setStdInfo})은 규격서대로 동작한다. 없던 것은
 * <b>관리자가 보는 쪽</b>이라 여기만 새로 붙인다.
 *
 * <h2>★ 전환은 「입학확정」에서만 한다</h2>
 * 상담 상태 6종과 재적 상태는 다른 축이고 <b>둘이 만나는 지점이 입학확정 하나</b>다.
 * 아무 상태에서나 되게 두면 상담취소된 신청자에게 학번이 나가고, 학번은 회수되지 않는다.
 *
 * <h2>지점은 파라미터로 받지 않는다</h2>
 * 권한에서 판단한다(§7). 전 지점 권한자면 전부 보이고, 아니면 자기 지점만 보인다.
 */
@Tag(name = "관리자 · 입학예약(대기자) 관리 (F-4.2-1)")
@RestController
@RequestMapping("/api/v1/admin/admission-reservations")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminAdmissionReservationController {

    private final AdmissionReservationAdminService adminService;
    private final com.dlab.domain.share.service.PublicWaitlistShareService waitlistShareService;

    /** 목록. 상태·이름·연락처로 좁힌다. */
    @GetMapping
    public ApiResponse<List<ReservationView>> list(@CurrentAccount AuthPrincipal me,
                                                   @RequestParam short year,
                                                   @RequestParam(required = false) ConsultStatus status,
                                                   @RequestParam(required = false) String keyword) {
        return ApiResponse.success(adminService.search(me, year, status, keyword)
                .stream().map(ReservationView::from).toList());
    }

    /**
     * 같은 사람의 입학예약 찾기 — <b>중복 등록 방지</b> (피드백 6-3①).
     *
     * <p>홈페이지로 입학상담예약을 넣은 학생을 데스크에서 그냥 등록하면 <b>같은 사람이
     * 두 건으로 쌓인다.</b> 화면이 저장 전에 「같은 분의 입학예약이 있습니다」를 띄우려면
     * 이 경로가 필요하다 — 저장 차단만 있으면 담당자는 전부 입력한 뒤에야 알게 되고,
     * 차단 응답에는 <b>이름·연락처가 실리지 않으므로</b>(로그에 남는다) 안내 문구를 만들
     * 재료도 없다.
     *
     * <p>★ <b>자동으로 합치지 않는다.</b> 동명이인이 실제로 있고 전환은 학번을 채번해
     * 되돌릴 수 없다 — 찾아서 알려주고 사람이 고른다.
     *
     * <p>판정은 <b>[이름 + 생년월일] 또는 [이름 + 연락처]</b>다. 이름만으로는 동명이인이
     * 전부 걸리고, 셋을 모두 요구하면 생년월일을 안 받은 건에서 아무것도 안 걸린다.
     *
     * @param birth     {@code yyyyMMdd} 또는 {@code yyyy-MM-dd}. <b>숫자만 뽑아 비교</b>하므로
     *                  형식이 달라도 된다 — 규격서 필드가 문자열이라 날짜로 바꿔 비교하면
     *                  형식이 다른 값에서 터진다
     * @param parentTel 학부모 또는 학생 연락처. 하이픈이 있어도 된다
     * @param year      비우면 올해
     */
    @GetMapping("/duplicates")
    public ApiResponse<List<DuplicateView>> duplicates(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year,
            @RequestParam String name,
            @RequestParam(required = false) String birth,
            @RequestParam(required = false) String parentTel) {
        return ApiResponse.success(
                adminService.duplicates(me, academyId, year, name, birth, parentTel)
                        .stream().map(DuplicateView::from).toList());
    }

    /**
     * 상세 — <b>접수 폼으로 들어온 항목까지</b> 함께 내린다.
     *
     * <p>목록과 같은 모양이면 카드에 적을 것이 이름·학교·연락처뿐이어서, <b>담당자가 전화를
     * 걸기 전에 볼 정보가 없다.</b> 생년·등원 희망일·유입경로가 저장은 되는데 응답에만
     * 빠져 있었다.
     *
     * <p><b>목록에는 싣지 않는다.</b> 칸반이 수백 건을 한 번에 받는 화면이라, 행마다 주소·동의
     * 항목까지 실으면 목록이 몇 배로 커진다.
     */
    @GetMapping("/{reservationId}")
    public ApiResponse<ReservationDetailView> detail(@CurrentAccount AuthPrincipal me,
                                                     @PathVariable Long reservationId) {
        return ApiResponse.success(
                ReservationDetailView.from(adminService.get(me, reservationId)));
    }

    /** 상태 변경. <b>이력이 남는다</b> — 없으면 "왜 미등록으로 바뀌었나" 에 답할 수 없다. */
    @PostMapping("/{reservationId}/status")
    public ApiResponse<ReservationView> changeStatus(@CurrentAccount AuthPrincipal me,
                                                     @PathVariable Long reservationId,
                                                     @Valid @RequestBody ChangeStatus request) {
        return ApiResponse.success(ReservationView.from(adminService.changeStatus(
                me, reservationId, request.status(), request.reason())));
    }

    /**
     * 공개 공유 링크 발급 — <b>「대기자 안내」 알림톡 버튼이 가리킬 주소</b>.
     *
     * <p>응답의 {@code token} 으로 {@code /waitlist/:token} 주소를 만든다. 학생·학부모가
     * 로그인 없이 열어 <b>등록 의사를 버튼으로 답한다</b> — 알림톡은 단방향이라 답장이
     * 우리 시스템에 들어오지 않고, 지금은 전화를 받아 직원이 손으로 적는다.
     *
     * <p>★★ <b>기한을 요청에서 받지 않는다.</b> 화면마다 다른 기한을 보내면 「왜 이
     * 링크만 일찍 닫혔나」를 추적할 수 없다 — <b>서버 기본값(7일)</b>을 쓴다.
     *
     * <p>★★ <b>여러 번 불러도 같은 토큰이 나온다.</b> 새로 만들면 이미 보낸 알림톡의
     * 링크가 죽는다. <b>기한도 처음 정한 날짜를 그대로 둔다</b> — 누를 때마다 7일이 다시
     * 붙으면 기한이 사실상 없어진다. 답변 1회 고정은 토큰이 아니라 <b>예약 건</b>에서 지킨다.
     */
    @PostMapping("/{reservationId}/share")
    public ApiResponse<ShareResponse> share(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long reservationId) {
        var token = waitlistShareService.share(me, reservationId);
        return ApiResponse.success(new ShareResponse(token.getToken(), token.getExpiresAt()));
    }

    /** @param expiresAt 응답 기한. 공개 페이지의 {@code deadline} 과 같은 값이다 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "WaitlistShareResponse")
    public record ShareResponse(String token, Instant expiresAt) {
    }

    @GetMapping("/{reservationId}/status-logs")
    public ApiResponse<List<StatusLogView>> statusLogs(@CurrentAccount AuthPrincipal me,
                                                       @PathVariable Long reservationId) {
        return ApiResponse.success(adminService.statusLogs(me, reservationId)
                .stream().map(StatusLogView::from).toList());
    }

    /** 메모. <b>지점 메모는 그 지점만, 본사는 전체</b>를 본다. */
    @GetMapping("/{reservationId}/memos")
    public ApiResponse<List<MemoView>> memos(@CurrentAccount AuthPrincipal me,
                                             @PathVariable Long reservationId) {
        return ApiResponse.success(adminService.memos(me, reservationId)
                .stream().map(MemoView::from).toList());
    }

    @PostMapping("/{reservationId}/memos")
    public ApiResponse<MemoView> addMemo(@CurrentAccount AuthPrincipal me,
                                         @PathVariable Long reservationId,
                                         @Valid @RequestBody SaveMemo request) {
        return ApiResponse.success(MemoView.from(
                adminService.addMemo(me, reservationId, request.content())));
    }

    @DeleteMapping("/memos/{memoId}")
    public ApiResponse<Void> deleteMemo(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long memoId) {
        adminService.deleteMemo(me, memoId);
        return ApiResponse.empty();
    }

    /**
     * 정식 접수 전환 — 신청자를 학생으로 만든다 (F-4.1-4).
     *
     * <p>★ <b>「입학확정」 상태에서만</b> 되고, <b>한 번만</b> 된다. 학번 채번은 기존 신규
     * 접수 경로를 그대로 탄다.
     *
     * <p>★ <b>이미 등록된 같은 학생이 있으면 멈춘다</b>(피드백 6-3①, {@code 409}). 신규접수
     * 쪽만 막으면 <b>데스크가 먼저 등록하는 순서에서 중복이 그대로 통과한다.</b> 담당자가
     * 「다른 사람입니다」를 고르면 {@code confirmedNotDuplicate}를 실어 다시 보낸다.
     *
     * @param grade 신청서의 학년 표기(1·2·3·N)로 판단되지 않을 때만 지정한다
     */
    @PostMapping("/{reservationId}/convert")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<ConvertedView> convert(@CurrentAccount AuthPrincipal me,
                                              @PathVariable Long reservationId,
                                              @RequestBody(required = false) Convert request) {
        var enrollment = adminService.convert(me, reservationId,
                request == null ? null : request.grade(),
                request == null ? null : request.admissionDate(),
                request != null && request.confirmedNotDuplicate());
        return ApiResponse.success(new ConvertedView(enrollment.getId(),
                enrollment.getStudentNo(), enrollment.getStudent().getName()));
    }

    public record ChangeStatus(@NotNull(message = "상태는 필수입니다.") ConsultStatus status,
                               @Size(max = 500) String reason) {
    }

    public record SaveMemo(@NotBlank(message = "내용은 필수입니다.")
                           @Size(max = 2000) String content) {
    }

    /**
     * @param confirmedNotDuplicate 담당자가 「다른 사람입니다」를 고른 경우 {@code true}.
     *                              <b>자동 병합을 하지 않으므로 사람의 선택이 유일한
     *                              통과 수단이다</b> — 기본값({@code false})은 차단이다
     */
    public record Convert(GradeType grade, LocalDate admissionDate,
                          boolean confirmedNotDuplicate) {
    }

    /**
     * 중복 후보 한 건 — 화면의 「같은 분의 입학예약이 있습니다」 카드.
     *
     * <p>이름·생년·연락처를 <b>가리지 않고</b> 내린다. 이 화면은 담당자가 "같은 사람인가"를
     * 눈으로 판단하는 자리라 가리면 판단이 불가능하고, 대기자 목록·상세가 이미 같은
     * 값을 그대로 내린다.
     *
     * @param matchedBy 무엇이 같아서 걸렸는가({@code BIRTH}/{@code TEL}). 카드가
     *                  「생년월일이 같습니다」까지 보여줘야 사람이 판단할 수 있다
     * @param birth     규격서 형식 그대로의 {@code yyyyMMdd} 문자열이다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AdmissionDuplicateView")
    public record DuplicateView(Long reservationId, String rsvCd, short year, Long academyId,
                                String studentName, String birth, String studentTel,
                                String parentTel, ConsultStatus status, String statusName,
                                AdmissionDuplicateService.MatchedBy matchedBy,
                                Instant createdAt) {

        static DuplicateView from(AdmissionDuplicateService.Match match) {
            AdmissionReservation r = match.reservation();
            return new DuplicateView(r.getId(), r.getRsvCd(), r.getYear(),
                    r.getAcademy().getId(), r.getStudentName(), r.getBirth(),
                    r.getStudentTel(), r.getParentTel(),
                    r.getConsultStatus(), r.getConsultStatus().displayName(),
                    match.matchedBy(), r.getCreatedAt());
        }
    }

    /**
     * @param statusName 화면에 그대로 쓰는 표기. 하드코딩하면 화면마다 갈린다
     * @param converted  이미 학생으로 전환됐는가. 버튼을 감추는 근거다
     */
    public record ReservationView(Long id, String rsvCd, short year, Long academyId,
                                  String studentName, String studentTel, String parentTel,
                                  String stdGrade, String schoolName,
                                  ConsultStatus status, String statusName,
                                  boolean converted, Long enrollmentId, String studentNo,
                                  /**
                                   * 등원 희망일 (피드백 5-3).
                                   *
                                   * <p>★ 상세에만 있던 값이다. 대기자 화면이 <b>등원 예정일별
                                   * 인원</b>을 세야 해서 목록에도 싣는다 — 없으면 화면이
                                   * 행마다 상세를 부르게 된다.
                                   *
                                   * <p>⚠️ 규격서 필드라 <b>문자열</b>이다({@code yyyyMMdd}).
                                   * 날짜로 바꾸지 않는다 — 들어온 값을 그대로 보여야
                                   * 담당자가 이상한 입력을 알아본다.
                                   */
                                  String admissionDate,
                                  Instant createdAt) {

        static ReservationView from(AdmissionReservation r) {
            return new ReservationView(r.getId(), r.getRsvCd(), r.getYear(),
                    r.getAcademy().getId(), r.getStudentName(), r.getStudentTel(),
                    r.getParentTel(), r.getStdGrade(), r.getSchNmHigh(),
                    r.getConsultStatus(), r.getConsultStatus().displayName(),
                    r.converted(),
                    r.getEnrollment() == null ? null : r.getEnrollment().getId(),
                    r.getEnrollment() == null ? null : r.getEnrollment().getStudentNo(),
                    r.getAdmDt(),
                    r.getCreatedAt());
        }
    }

    /**
     * 상세. 목록({@link ReservationView})에 접수 폼 항목을 더한 것이다.
     *
     * <p>⚠️ <b>코드 항목은 숫자로 내려간다</b>(출신학원·유입경로·전형·입학기준). 값 목록을
     * 아직 받지 못해 이름이 없다 — 이름은 {@code GET /api/v1/admin/admission-codes}로
     * 따로 받아 화면에서 잇는다. 응답에 이름을 함께 싣지 않는 이유는, 목록·필터에서도 같은
     * 이름이 필요한데 행마다 같은 문자열이 반복되고 <b>"언제의 이름인가"를 응답이
     * 결정해 버리기</b> 때문이다.
     *
     * <p>⚠️ 연락처·생년·주소를 <b>가리지 않고 내린다.</b> 이 화면은 담당자가 지원자에게
     * 전화를 거는 업무라 연락처 열람이 업무 자체이고, 목록이 이미 그렇게 돌고 있다.
     * 권한 매트릭스가 확정되면 열람 범위를 함께 정리한다.
     *
     * @param admissionDate 등원 희망일({@code yyyyMMdd} 문자열). 규격서 형식 그대로다
     * @param track         계열. ⚠️ 수집·노출을 멈추기로 한 항목인데 수신 전문에 남아 있어
     *                      값이 들어오는 경우가 있다 — 화면에 쓰지 말 것
     */
    public record ReservationDetailView(
            Long id, String rsvCd, short year, Long academyId,
            String studentName, String studentTel, String parentTel,
            String stdGrade, String schoolName, Integer schoolCode,
            ConsultStatus status, String statusName,
            boolean converted, Long enrollmentId, String studentNo,
            String birth, String gender, String admissionDate, Short track,
            Integer previousAcademy,
            Integer foundPath, String foundPathText,
            Integer examType, Integer admissionStandard,
            Short schoolType, java.math.BigDecimal schoolRecord,
            String universityName, Short universityGrade,
            Short rejectReason, String rejectReasonText,
            String zipCode, String address, String addressDetail,
            boolean agreePrivacy, boolean agreeMarketing,
            Instant createdAt) {

        static ReservationDetailView from(AdmissionReservation r) {
            return new ReservationDetailView(
                    r.getId(), r.getRsvCd(), r.getYear(), r.getAcademy().getId(),
                    r.getStudentName(), r.getStudentTel(), r.getParentTel(),
                    r.getStdGrade(), r.getSchNmHigh(), r.getSchCdHigh(),
                    r.getConsultStatus(), r.getConsultStatus().displayName(),
                    r.converted(),
                    r.getEnrollment() == null ? null : r.getEnrollment().getId(),
                    r.getEnrollment() == null ? null : r.getEnrollment().getStudentNo(),
                    r.getBirth(), r.getGender(), r.getAdmDt(), r.getGeyulGb(),
                    r.getSchCd(),
                    r.getFindGb(), r.getFindTxt(),
                    r.getPreTest(), r.getAdmiSt(),
                    r.getNasinSt(), r.getNasinSc(),
                    r.getUniNm(), r.getUniGd(),
                    r.getIntrSt(), r.getIntrTxt(),
                    r.getZip(), r.getAddr1(), r.getAddr2(),
                    r.isAgreeAd(), r.isPromoAd(),
                    r.getCreatedAt());
        }
    }

    /**
     * @param actorType  ★ <b>{@code STUDENT}(학생 응답) · {@code STAFF}(직원 변경)</b>.
     *                   {@code changedBy} 로는 구분되지 않는다 — 공개 링크는 인증 주체가
     *                   없어 시스템 계정(0)으로 기록되고 배치도 같은 값이다
     * @param actorLabel 화면에 그대로 쓸 문구. 화면이 enum 을 다시 번역하지 않게 한다
     */
    public record StatusLogView(Long id, ConsultStatus fromStatus, ConsultStatus toStatus,
                                String reason, Long changedBy, Instant changedAt,
                                com.dlab.domain.admission.entity.StatusActorType actorType,
                                String actorLabel) {

        static StatusLogView from(AdmissionReservationStatusLog l) {
            return new StatusLogView(l.getId(), l.getFromStatus(), l.getToStatus(),
                    l.getReason(), l.getCreatedBy(), l.getCreatedAt(),
                    l.getActorType(), l.getActorType().displayName());
        }
    }

    public record MemoView(Long id, String content, Long writerId, Instant createdAt) {

        static MemoView from(AdmissionReservationMemo m) {
            return new MemoView(m.getId(), m.getContent(), m.getCreatedBy(), m.getCreatedAt());
        }
    }

    public record ConvertedView(Long enrollmentId, String studentNo, String studentName) {
    }
}
