package com.dlab.api.admin.vocab;

import com.dlab.common.excel.ColumnMapping;
import com.dlab.common.excel.ExcelExporter;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.vocab.service.VocabResultBoardService;
import com.dlab.domain.vocab.service.VocabWrongAnswerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통합관리 — D:VOCA 결과 조회 (v1 7장).
 *
 * <h2>★ 담임은 맡은 학생만 본다</h2>
 * {@code HomeroomScopeService} 를 그대로 쓴다 — v1 7장 각주가 <i>"반·담임 정보는 디랩
 * 통합관리에 이미 있는 데이터를 쓴다. 새로 만들지 않는다"</i>고 못 박았고, 새로 짜면
 * <b>출결·루틴 화면과 다른 학생 집합</b>이 나온다.
 *
 * <h2>★★ 미응시와 0점이 구분된다</h2>
 * 이 화면의 목적이 <i>"참여 여부와 점수"</i>라 둘이 섞이면 쓸 수 없다. 점수가
 * {@code null} 이면 <b>안 본 것</b>, 0 이면 <b>보고 다 틀린 것</b>이다.
 *
 * <h2>★ 오답 확인은 「인정 표현 추가」로 이어진다</h2>
 * 주관식 채점을 넓히는 <b>유일한 경로</b>다(v2 5.3). 넣는 곳은
 * {@code PUT /admin/vocab/words/&#123;id&#125;/meanings} 다.
 */
@Tag(name = "통합관리 · D:VOCA 결과 조회")
@RestController
@RequestMapping("/api/v1/admin/vocab/results")
@RequiredArgsConstructor
public class AdminVocabResultController {

    private static final String VIEWER = "hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')";

    private final VocabResultBoardService boardService;
    private final VocabWrongAnswerService wrongAnswerService;
    private final ExcelExporter excelExporter;

    /** 학생별 결과 — 참여 여부와 점수. */
    @Operation(summary = "학생별 결과", description = "참여율·정답률 평균. ★ 담임은 맡은 학생만")
    @GetMapping("/students")
    @PreAuthorize(VIEWER)
    public ApiResponse<List<AdminVocabResultResponse.StudentRow>> students(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long classId) {
        return ApiResponse.success(
                boardService.students(me, academyId, year, from, to, classId).stream()
                        .map(AdminVocabResultResponse.StudentRow::from).toList());
    }

    /** 유닛별 결과 — ★ 금요일 누적은 제외한다. */
    @Operation(summary = "유닛별 결과", description = "★ 누적 회차 제외 — 어느 유닛 점수인지 가를 수 없다")
    @GetMapping("/units")
    @PreAuthorize(VIEWER)
    public ApiResponse<List<AdminVocabResultResponse.UnitRow>> units(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long classId) {
        return ApiResponse.success(
                boardService.units(me, academyId, year, from, to, classId).stream()
                        .map(AdminVocabResultResponse.UnitRow::from).toList());
    }

    /** 회차별 결과. */
    @Operation(summary = "회차별 결과", description = "회차마다 몇 명이 봤는지")
    @GetMapping("/rounds")
    @PreAuthorize(VIEWER)
    public ApiResponse<List<AdminVocabResultResponse.RoundRow>> rounds(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long classId) {
        return ApiResponse.success(
                boardService.rounds(me, academyId, year, from, to, classId).stream()
                        .map(AdminVocabResultResponse.RoundRow::from).toList());
    }

    /**
     * 엑셀 내려받기 (v1 7장) — <i>"결과 조회 화면 그대로"</i>.
     *
     * <p>★ <b>이름을 마스킹하지 않는다.</b> 학생 이름은 개인정보 마스킹 대상(전화·주소·
     * 생년월일)이 아니고, 담임이 자기 반 학생 성적을 보는 화면이라 가리면 쓸 수 없다.
     *
     * <p>★ <b>미응시 칸은 빈 칸이다.</b> 0 으로 채우면 엑셀에서 평균을 낼 때
     * <b>안 본 학생이 0점으로 섞인다.</b>
     */
    @Operation(summary = "엑셀 내려받기", description = "학생별 결과를 화면 그대로")
    @GetMapping("/students/excel")
    @PreAuthorize(VIEWER)
    public ResponseEntity<byte[]> excel(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Short year,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long classId) {
        var rows = boardService.students(me, academyId, year, from, to, classId);

        byte[] body = excelExporter.export("단어시험결과", MAPPING, rows, r -> List.of(
                nullSafe(r.studentNo()),
                nullSafe(r.studentName()),
                nullSafe(r.className()),
                nullSafe(r.homeroomTeacherName()),
                String.valueOf(r.openCount()),
                String.valueOf(r.takenCount()),
                // ★ 미응시는 빈 칸이다 — 0 으로 채우면 엑셀 평균에 섞인다
                r.participationRate() == null ? "" : r.participationRate() + "%",
                r.averageScore() == null ? "" : r.averageScore() + "%",
                r.lastScore() == null ? "" : r.lastScore() + "%",
                r.lastTakenOn() == null ? "" : r.lastTakenOn().toString()));

        String filename = URLEncoder.encode("단어시험_결과.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .body(body);
    }

    /** 오답이 많은 단어 — ★ 주관식만이다. */
    @Operation(summary = "단어별 오답 현황",
            description = "★ 주관식만. 객관식 오답은 보기 중 하나라 인정 표현이 될 수 없다")
    @GetMapping("/wrong-words")
    @PreAuthorize(VIEWER)
    public ApiResponse<List<AdminVocabResultResponse.WrongWordRow>> wrongWords(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Short unitNo) {
        return ApiResponse.success(wrongAnswerService.words(me, unitNo).stream()
                .map(AdminVocabResultResponse.WrongWordRow::from).toList());
    }

    /**
     * 그 단어에 학생들이 쓴 틀린 답 — <b>같은 답끼리 묶어</b> 많은 순.
     *
     * <p>★ 여기서 {@code PUT /admin/vocab/words/&#123;id&#125;/meanings} 로 이어진다.
     */
    @Operation(summary = "학생이 쓴 틀린 답",
            description = "같은 답끼리 묶어 많은 순. ★ 인정 표현 추가로 이어진다")
    @GetMapping("/wrong-words/{wordId}/givens")
    @PreAuthorize(VIEWER)
    public ApiResponse<List<AdminVocabResultResponse.WrongGivenRow>> givens(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long wordId) {
        return ApiResponse.success(wrongAnswerService.givens(me, wordId).stream()
                .map(AdminVocabResultResponse.WrongGivenRow::from).toList());
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static final ColumnMapping MAPPING = ColumnMapping.builder()
            .required("studentNo", "학번")
            .required("studentName", "이름")
            .optional("className", "반")
            .optional("homeroomTeacherName", "담임")
            .optional("openCount", "공개 회차")
            .optional("takenCount", "응시 회차")
            .optional("participationRate", "참여율")
            .optional("averageScore", "정답률 평균")
            .optional("lastScore", "최근 정답률")
            .optional("lastTakenOn", "최근 응시일");
}
