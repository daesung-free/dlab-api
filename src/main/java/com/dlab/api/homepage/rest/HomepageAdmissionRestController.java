package com.dlab.api.homepage.rest;

import com.dlab.common.config.HomepageProperties;
import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.response.ApiResponse;
import com.dlab.domain.admission.service.AdmissionReservationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 홈페이지 입학예약 수신 — <b>B안(REST)</b>.
 *
 * <h2>A안과 무엇이 다른가</h2>
 * 같은 일을 하는 입구가 둘이다. {@code /dlab/**}(A안)는 대성전산 규격을 그대로 흉내 낸 것이라
 * <b>실패해도 HTTP 200</b>이고 응답 안의 {@code code} 숫자로 성패를 가린다. 홈페이지가 그
 * 규칙을 알아야 하고, 나중에 신청이 안 들어왔을 때 원인을 찾기 어렵다.
 *
 * <p>여기는 <b>실패가 실패로 보인다</b> — 성공은 200, 잘못된 요청은 400, 서버 문제는 500이고
 * 이유가 본문에 글로 들어간다.
 *
 * <p><b>A안을 지우지 않는다.</b> 홈페이지가 전환하기 전까지 그쪽으로 들어오고, 전환은
 * 그쪽 일정(11/30)에 달려 있다.
 *
 * <h2>★ 패키지를 나눈 이유</h2>
 * A안({@code api.homepage.admission})은 {@code DsaExceptionHandler} 가 잡는 구획이다 —
 * 실패해도 {@code {code, message}} 형식으로 200을 돌려준다. 같은 패키지에 두면 B안의
 * 401·400 이 그 형식으로 바뀌어 <b>실패가 다시 200으로 보인다</b>(테스트가 먼저 잡았다).
 * 그래서 패키지를 가른다.
 *
 * <h2>★ 멱등이다</h2>
 * {@code requestId}가 같으면 몇 번을 보내도 한 건이다. 전송이 실패해 다시 보내는 것이
 * 정상 동작이라, 이것이 없으면 <b>재전송이 곧 중복 접수</b>가 된다.
 */
@Tag(name = "홈페이지 · 입학예약 (B안)")
@RestController
@RequestMapping("/api/v1/homepage")
@RequiredArgsConstructor
public class HomepageAdmissionRestController {

    private final HomepageProperties properties;
    private final AdmissionReservationService admissionService;

    /**
     * 입학예약 접수.
     *
     * <p>같은 {@code requestId}가 다시 오면 저장하지 않고 <b>먼저 들어온 건의 접수번호</b>를
     * 그대로 돌려준다 — 홈페이지는 성공으로 처리하면 된다.
     */
    @PostMapping("/admissions")
    public ApiResponse<Receipt> create(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody AdmissionRequest request) {

        verify(authorization);
        String reservationNo = admissionService.save(request.toCommand());
        return ApiResponse.success(new Receipt(reservationNo));
    }

    /**
     * 성적표 파일.
     *
     * <p>접수번호를 먼저 받아야 올릴 수 있다 — 어느 신청의 파일인지 알아야 해서다.
     * 본문은 {@code data:} 접두사가 있든 없든 Base64 문자열을 받는다.
     */
    @PostMapping("/admissions/{reservationNo}/files")
    public ApiResponse<Void> uploadFile(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable String reservationNo,
            @Valid @RequestBody FileRequest request) {

        verify(authorization);
        admissionService.saveFile(reservationNo, request.file());
        return ApiResponse.empty();
    }

    /**
     * 선택 항목 값 목록 — 홈페이지 화면의 드롭다운을 채운다.
     *
     * <p>⚠️ <b>값 목록을 아직 받지 못했다.</b> 지금은 빈 배열이 나간다
     * (출신학원 {@code ACAD} · 지원기준 {@code ADMI} · 전형 {@code EXAM} · 알게된 경로 {@code FIND}).
     */
    @GetMapping("/codes")
    public ApiResponse<List<CodeView>> codes(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String group,
            // ★ 지점마다 값이 다를 수 있어 생략할 수 없다 — 없으면 어느 지점 목록인지 정해지지 않는다
            @RequestParam String academyCode) {

        verify(authorization);
        return ApiResponse.success(admissionService.commonCodes(academyCode, group).stream()
                .map(c -> new CodeView(c.getCode(), c.getName()))
                .toList());
    }

    /**
     * 고정 키 확인.
     *
     * <p><b>키가 설정돼 있지 않으면 전부 거부한다.</b> 조용히 통과시키면 키를 안 넣은 서버에서
     * 지원자 정보가 무인증으로 열린다(A안 자격증명과 같은 판단).
     */
    private void verify(String authorization) {
        String expected = properties.getApiKey();
        if (expected == null || expected.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "홈페이지 연동 키가 설정되지 않았습니다.");
        }
        String presented = authorization == null ? null
                : authorization.replaceFirst("(?i)^Bearer\\s+", "").trim();
        if (presented == null || !expected.equals(presented)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** @param reservationNo 접수번호. 파일 업로드와 조회가 이 값을 쓴다 */
    public record Receipt(String reservationNo) {
    }

    public record CodeView(String code, String name) {
    }

    /** @param file {@code data:} 접두사가 있어도 되고 Base64 본문만 보내도 된다 */
    public record FileRequest(@NotBlank String file) {
    }

    /**
     * 접수 항목.
     *
     * <p>이름을 A안의 축약어({@code rsv_nm}·{@code std_tel})가 아니라 읽을 수 있는 말로 둔다 —
     * 새로 붙이는 쪽이 옛 약어를 배울 이유가 없다.
     */
    public record AdmissionRequest(
            /** ★ 신청 고유번호. 재전송 시 같은 값을 보내면 한 건으로 처리된다 */
            @NotBlank @Size(max = 64) String requestId,
            @NotBlank String academyCode,
            Integer year,
            @NotBlank String name,
            @NotBlank String studentTel,
            /** ★ 비어 있을 수 없다 — 학부모 연락처가 없으면 상담 전화를 걸 데가 없다 */
            @NotBlank String parentTel,
            String gender,
            String birth,
            Short track,
            String admissionDate,
            String grade,
            Integer examType,
            Integer admissionStandard,
            Short schoolType,
            BigDecimal schoolRecord,
            String universityName,
            Short universityGrade,
            Short rejectReason,
            String rejectReasonText,
            Integer foundPath,
            String foundPathText,
            Integer previousAcademy,
            Integer highSchoolCode,
            String highSchoolName,
            String zipCode,
            String address,
            String addressDetail,
            /** 개인정보 수집 동의 — {@code Y}/{@code N} */
            String agreePrivacy,
            /** 광고 수신 동의 — {@code Y}/{@code N} */
            String agreeMarketing) {

        AdmissionReservationService.SaveCommand toCommand() {
            return new AdmissionReservationService.SaveCommand(
                    academyCode,
                    year == null ? null : String.valueOf(year),
                    name, studentTel, parentTel, gender, birth, track, admissionDate,
                    examType, admissionStandard, schoolType, schoolRecord,
                    universityName, universityGrade, rejectReason, rejectReasonText,
                    foundPath, foundPathText, previousAcademy,
                    zipCode, address, addressDetail,
                    highSchoolCode, highSchoolName,
                    agreePrivacy, agreeMarketing, grade,
                    requestId);
        }
    }
}
