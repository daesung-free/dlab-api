package com.dlab.api.admin.statistics;

import com.dlab.common.excel.ColumnMapping;
import com.dlab.common.excel.ExcelExporter;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.statistics.service.StatisticsService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 통계·관리자 대시보드 (F-4.11-11).
 *
 * <p><b>지점 스코프를 요청에서 그대로 믿지 않는다.</b> 전 지점 권한자만 전체를 볼 수 있고,
 * 지점 관리자는 {@code academyId}를 비우거나 남의 지점을 넣어도 자기 지점으로 고정된다.
 *
 * <p><b>실적 통계는 아직 없다</b> — 실적 관리(F-4.10-6)가 미구현이다.
 */
@Tag(name = "관리자 · 통계·대시보드 (F-4.11-11)")
@RestController
@RequestMapping("/api/v1/admin/statistics")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF','READONLY')")
public class AdminStatisticsController {

    private final StatisticsService statisticsService;
    private final ExcelExporter excelExporter;

    /**
     * 대시보드 한 번에.
     *
     * @param academyId 전 지점 권한자만 의미가 있다. 비우면 전 지점
     */
    @GetMapping
    public ApiResponse<StatisticsService.Overview> overview(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(
                statisticsService.overview(me, academyId, year, from, to));
    }

    /**
     * 반별·계열별·월별 인원 (F-C-2 학원생 현황).
     *
     * <p><b>대시보드 개요와 축이 다르다.</b> {@code GET /statistics}는 "지금 전체가 어떤가"
     * 하나뿐이라 "어디에 몇 명인가"를 못 그린다. 전 원생을 내려받아 화면에서 세는 방식은
     * 원생 수가 늘수록 그대로 느려진다.
     *
     * <p><b>축마다 채워지는 칸이 다르다</b> — 정원·충원율은 반에만 있고,
     * 증감은 월별에만 있다. 없는 축에서 0으로 채우면 화면이 "정원 0명"·"증감 없음"으로
     * 잘못 읽는다.
     *
     * @param groupBy {@code CLASS} 반별 · {@code TRACK} 계열별 · {@code MONTH} 월별 추이
     * @param asOf    월별에서 "오늘"을 대신할 기준일. 비우면 오늘이다
     */
    @GetMapping("/students")
    public ApiResponse<List<StatisticsService.GroupRow>> students(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam StatisticsService.GroupBy groupBy,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {

        return ApiResponse.success(
                statisticsService.group(me, academyId, year, groupBy, asOf));
    }

    /**
     * 순공시간 랭킹 엑셀.
     *
     * <p><b>이름을 마스킹하지 않는다</b> — 관리자 전용 통계이고, 마스킹하면 누가 상위인지
     * 알 수 없어 상담·시상 자료로 쓸 수 없다. 대신 연락처 같은 개인정보는 애초에 안 넣는다.
     */
    @GetMapping("/study-time/export")
    public ResponseEntity<byte[]> exportStudyTime(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "100") int size) {

        var ranking = statisticsService.studyTimeRanking(me, academyId, from, to, size);

        // 순위를 서버가 매긴다 — 엑셀에서 다시 정렬하면 순위가 어긋난다
        var rows = IntStream.range(0, ranking.size())
                .mapToObj(i -> List.of(String.valueOf(i + 1),
                        ranking.get(i).studentNo(),
                        ranking.get(i).studentName(),
                        String.valueOf(ranking.get(i).studyMinutes()),
                        format(ranking.get(i).studyMinutes())))
                .toList();

        byte[] body = excelExporter.export("순공시간", MAPPING, rows, r -> r);

        String filename = URLEncoder.encode("순공시간_랭킹.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .body(body);
    }

    /** {@code "N시간 M분"}. 분만 주면 화면에서 매번 나눠야 한다. */
    private String format(long minutes) {
        return "%d시간 %02d분".formatted(minutes / 60, minutes % 60);
    }

    private static final ColumnMapping MAPPING = ColumnMapping.builder()
            .optional("rank", "순위")
            .required("studentNo", "학번")
            .required("name", "이름")
            .optional("studyMinutes", "순공(분)")
            .optional("studyTime", "순공시간");
}
