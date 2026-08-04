package com.dlab.api.kiosk.student;

import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.api.kiosk.dto.RfidMonthRequest;
import com.dlab.api.kiosk.dto.RfidRequest;
import com.dlab.api.kiosk.dto.TokenOnlyRequest;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskStudentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 키오스크 학생·지점·학부모 조회.
 *
 * <p><b>세 가지가 일반 컨트롤러와 다르다</b>(CLAUDE.md §5·§7):
 * <ol>
 *   <li>경로에 {@code /api/v1} prefix가 없다 — 키오스크가 하드코딩하고 있다</li>
 *   <li>응답이 {@code ApiResponse}가 아니라 {@link DsaResponse}다</li>
 *   <li><b>전부 POST다.</b> 조회여도 GET이 아니다 — 파라미터가 본문으로 온다</li>
 * </ol>
 *
 * <p>토큰도 헤더가 아니라 본문에 있어서 Security 필터가 처리하지 못한다.
 * 여기서 {@link DsaTokenService#resolveAcademyId}로 직접 검증한다.
 */
@RestController
@RequestMapping("/kiosk")
@RequiredArgsConstructor
public class KioskStudentController {

    private final KioskStudentQueryService studentQueryService;
    private final DsaTokenService tokenService;

    /** 3.20 — 지점 학생 전체. 파라미터가 토큰뿐이라 <b>현재 연도 기준</b>으로 응답한다. */
    @PostMapping("/getStdInfoList")
    public DsaResponse studentList(@RequestBody TokenOnlyRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(studentQueryService.studentList(academyId));
    }

    /** 3.24 — 카드번호로 학생 연락처. */
    @PostMapping("/getStdInfo")
    public DsaResponse studentInfo(@RequestBody RfidRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(studentQueryService.studentPhone(academyId, request.rfidNo()));
    }

    /** 3.19 — 지점 목록. 요청 지점이 아니라 전 지점을 내린다. */
    @PostMapping("/getDlabList")
    public DsaResponse academyList(@RequestBody TokenOnlyRequest request) {
        tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(studentQueryService.academyList());
    }

    /** 3.25 — 보호자 연락처. */
    @PostMapping("/getParentHpList")
    public DsaResponse parentPhones(@RequestBody RfidRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(studentQueryService.parentPhones(academyId, request.rfidNo()));
    }

    /** 3.16 — 당월 사유신청 목록. */
    @PostMapping("/getRequestListStd")
    public DsaResponse requestList(@RequestBody RfidMonthRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(
                studentQueryService.requestList(academyId, request.rfidNo(), request.month()));
    }
}
