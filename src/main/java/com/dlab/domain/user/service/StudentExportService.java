package com.dlab.domain.user.service;

import com.dlab.common.excel.ExcelExporter;
import com.dlab.common.privacy.Masking;
import com.dlab.common.search.SearchScope;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import com.dlab.domain.user.repository.StudentSearchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 학생 명단 엑셀 Export (요구사항 F-4.9 · 실행가이드 P1-01).
 *
 * <p><b>★ 마스킹 기본 ON이다</b>(실행가이드 3.2). 전화번호는 {@code 010-****-1234}로 나간다.
 * 발주처가 개인정보 유출 조사·벌금 사례로 민감도가 높은 상태이므로 임의로 완화하지 않는다.
 *
 * <p><b>Import와 같은 {@link com.dlab.common.excel.ColumnMapping}을 쓴다.</b> 실무에서 가장 흔한
 * 흐름이 "내려받아 수정 후 재업로드"라 <b>왕복이 성립해야 한다</b> — 헤더를 따로 정의하면
 * 내려받은 파일이 그대로는 안 올라간다. 마스킹된 연락처는 업로드 쪽에서 "변경 없음"으로
 * 읽히므로({@link Masking}) 왕복해도 번호가 날아가지 않는다.
 *
 * <p>검색 조건을 그대로 받는다 — 화면에서 12개 조건으로 걸러 본 목록을 그대로 내려받는 게
 * 자연스럽고, 조건 없이 전체만 받게 하면 사용자가 엑셀에서 다시 거르게 된다.
 */
@Service
@RequiredArgsConstructor
public class StudentExportService {

    /**
     * 한 번에 내보낼 최대 인원. 재원생 전체가 대상이라 넉넉히 잡되 상한은 둔다 —
     * {@code ExcelExporter}가 스트리밍이라 메모리는 견디지만, 무제한이면 실수로 전 지점을
     * 뽑는 순간 응답이 몇 분씩 걸린다.
     */
    private static final int MAX_ROWS = 10_000;

    private final StudentSearchRepository searchRepository;
    private final ExcelExporter excelExporter;

    @Transactional(readOnly = true)
    public byte[] export(SearchScope scope, String keyword, GradeType grade, TrackType track,
                         EnrollmentStatus status, Long classId) {

        List<StudentEnrollment> rows = searchRepository
                .search(scope, keyword, grade, track, status, classId, PageRequest.of(0, MAX_ROWS))
                .getContent();

        return excelExporter.export("학생명단", StudentImportService.MAPPING, rows, this::toRow);
    }

    /**
     * 한 행. <b>{@code MAPPING}의 컬럼 순서와 정확히 같아야 한다</b> —
     * {@code exportHeaders()}가 그 순서로 헤더를 쓰기 때문에 어긋나면 값이 밀린다.
     */
    private List<String> toRow(StudentEnrollment e) {
        List<String> cells = new ArrayList<>();
        cells.add(e.getStudent().getName());
        cells.add(gradeLabel(e.getGrade()));
        cells.add(e.getStudent().getUniqueCode());
        // ★ 마스킹. 여기를 풀면 개인정보 규칙 위반이다
        cells.add(Masking.phone(e.getStudent().getPhone()));
        cells.add(trackLabel(e.getTrack()));
        cells.add(e.getStudent().getBirthDate() == null ? null
                : e.getStudent().getBirthDate().toString());
        cells.add(e.getStudent().getGender());
        cells.add(e.getStudent().getSchoolName());
        return cells;
    }

    /** 업로드가 받아들이는 표기로 내보낸다 — 왕복하려면 내보낸 값을 다시 읽을 수 있어야 한다. */
    private String gradeLabel(GradeType grade) {
        if (grade == null) {
            return null;
        }
        return switch (grade) {
            case HIGH2 -> "고2";
            case HIGH3 -> "고3";
            case N_SU -> "N수생";
            // 직원은 학생 명단에 안 나오지만(조회에서 제외) enum이 닫혀 있어야 한다
            case STAFF -> "직원";
        };
    }

    private String trackLabel(TrackType track) {
        if (track == null) {
            return null;
        }
        return switch (track) {
            case SCIENCE -> "자연";
            case HUMANITIES -> "인문";
            case ART -> "예체능";
            case COMMON -> "공통";
        };
    }
}
