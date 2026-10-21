package com.dlab.domain.university.service;

import com.dlab.common.excel.ColumnMapping;
import com.dlab.common.excel.ExcelReader;
import com.dlab.common.excel.ExcelRow;
import com.dlab.domain.university.entity.University;
import com.dlab.domain.university.entity.UniversityDepartment;
import com.dlab.domain.university.repository.UniversityDepartmentRepository;
import com.dlab.domain.university.repository.UniversityRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대학·학과 마스터 엑셀 적재 (디랩 1021 피드백 6-1).
 *
 * <h2>왜 마이그레이션이 아니라 업로드인가</h2>
 * 원본 파일명이 {@code 2026더프모지원대학학과코드_20260223} 이라 <b>연도마다 새 파일이
 * 온다.</b> 4,921행을 마이그레이션에 박으면 해마다 새 마이그레이션을 쓰게 되고,
 * <b>이미 적용된 마이그레이션은 수정할 수 없어</b> 오타 하나에도 파일을 또 만들어야 한다.
 *
 * <h2>★ 재업로드가 행을 늘리지 않는다</h2>
 * 같은 {@code (연도, 코드)} 는 <b>갱신</b>한다. 늘리면 검색 결과에 같은 대학이 두 번
 * 나오고, 어느 쪽이 최신인지 화면이 알 수 없다.
 *
 * <h2>⚠️ 코드를 숫자로 읽지 않는다</h2>
 * {@code '001'} 처럼 0으로 채워져 있다. {@code ExcelReader} 가 전부 문자열로 읽으므로
 * 그대로 쓰면 되지만, 숫자 서식 셀의 {@code .0} 꼬리는 그쪽에서 흡수된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UniversityImportService {

    static final ColumnMapping MAPPING = ColumnMapping.builder()
            .required("universityCode", "대학코드")
            .required("universityName", "대학명", "대학")
            .required("departmentCode", "학과코드")
            .required("departmentName", "학과명", "학과", "모집단위")
            .optional("trackCode", "계열코드")
            .optional("trackName", "계열명", "계열");

    private final ExcelReader excelReader;
    private final UniversityRepository universityRepository;
    private final UniversityDepartmentRepository departmentRepository;

    /**
     * 적재 결과.
     *
     * @param universities 대학 — 새로 넣은 수 / 이름이 바뀌어 갱신한 수
     * @param departments  학과 — 같은 기준
     * @param errors       행 번호와 사유. ★ <b>엑셀 기준 1-based</b> 다
     */
    public record ImportResult(int rowCount, Counts universities, Counts departments,
                               List<String> errors) {

        public record Counts(int inserted, int updated) {
        }
    }

    @Transactional
    public ImportResult importMaster(Short year, InputStream input) {
        var parsed = excelReader.read(input, MAPPING);

        // ★ 미리 읽어 둔다. 4,921행이라 행마다 조회하면 쿼리가 1만 번 넘게 나간다
        Map<String, University> universities = new HashMap<>();
        universityRepository.findByYearAndDeletedFalse(year)
                .forEach(u -> universities.put(u.getCode(), u));
        Map<String, UniversityDepartment> departments = new HashMap<>();
        departmentRepository.findByYearAndDeletedFalse(year)
                .forEach(d -> departments.put(key(d.getUniversityCode(), d.getCode()), d));

        int uniInserted = 0;
        int uniUpdated = 0;
        int deptInserted = 0;
        int deptUpdated = 0;
        List<String> errors = new ArrayList<>();
        List<University> newUniversities = new ArrayList<>();
        List<UniversityDepartment> newDepartments = new ArrayList<>();

        for (ExcelRow row : parsed.rows()) {
            if (row.isEmpty()) {
                continue;
            }
            String uniCode = row.requiredText("universityCode", "대학코드");
            String uniName = row.requiredText("universityName", "대학명");
            String deptCode = row.requiredText("departmentCode", "학과코드");
            String deptName = row.requiredText("departmentName", "학과명");
            if (row.hasError()) {
                row.errors().forEach(e ->
                        errors.add("%d행: %s".formatted(row.rowNumber(), e.message())));
                continue;
            }
            String trackCode = row.text("trackCode");
            String trackName = row.text("trackName");

            University university = universities.get(uniCode);
            if (university == null) {
                university = new University(year, uniCode, uniName);
                universities.put(uniCode, university);
                newUniversities.add(university);
                uniInserted++;
            } else if (!uniName.equals(university.getName())) {
                // ⚠️ 같은 코드에 다른 대학명이 오면 파일이 바뀐 것이다. 실측 충돌은 0건이지만
                //    연도가 바뀌면 캠퍼스 표기가 달라질 수 있다 — 최신 파일을 따른다
                university.rename(uniName);
                uniUpdated++;
            }

            // ★★ 대학코드까지 넣어야 유일하다 — 학과코드 503개가 여러 대학에서 쓰인다.
            //    학과코드만으로 키를 잡으면 뒤에 온 대학이 앞 대학 학과를 덮어쓴다
            String deptKey = key(uniCode, deptCode);
            UniversityDepartment department = departments.get(deptKey);
            if (department == null) {
                department = new UniversityDepartment(
                        year, uniCode, deptCode, deptName, trackCode, trackName);
                departments.put(deptKey, department);
                newDepartments.add(department);
                deptInserted++;
            } else if (!deptName.equals(department.getName())
                    || !java.util.Objects.equals(trackName, department.getTrackName())) {
                department.update(deptName, trackCode, trackName);
                deptUpdated++;
            }
        }

        universityRepository.saveAll(newUniversities);
        departmentRepository.saveAll(newDepartments);

        log.info("대학 마스터 적재: 연도={}, 행={}, 대학 신규={}/갱신={}, 학과 신규={}/갱신={}, 오류={}",
                year, parsed.rows().size(), uniInserted, uniUpdated,
                deptInserted, deptUpdated, errors.size());

        return new ImportResult(parsed.rows().size(),
                new ImportResult.Counts(uniInserted, uniUpdated),
                new ImportResult.Counts(deptInserted, deptUpdated),
                errors);
    }

    private static String key(String universityCode, String departmentCode) {
        return universityCode + ":" + departmentCode;
    }
}
