package com.dlab.common.search;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

/**
 * 이미 손에 있는 목록을 페이지로 자른다.
 *
 * <p><b>왜 DB 페이징이 아닌가.</b> 출결·상벌점·사유신청 화면은 <b>합계를 필터 전체 기준</b>으로
 * 함께 내려야 한다(상단 통계 타일). DB 에서 잘라 오면 합계를 따로 한 번 더 세야 하고,
 * 그 둘이 어긋나면 화면에 뜬 목록과 합계가 다르게 보인다.
 *
 * <p><b>size 를 안 보내면 자르지 않는다.</b> 이 화면들은 원래 조회 범위 전량을 내려 왔고
 * 클라이언트가 그 위에서 정렬·페이징을 한다. 갑자기 서버가 자르기 시작하면
 * <b>화면은 한 페이지만 정렬해놓고 전체가 정렬된 것처럼 보이게 된다</b> — 에러도 안 나고
 * 눈에도 안 띈다. 그래서 켜는 쪽이 명시적으로 요청하게 둔다.
 */
public final class PageSlicer {

    /** 한 번에 내리는 최대치. 이보다 크게 요청해도 여기서 멈춘다. */
    private static final int MAX_SIZE = 500;

    private PageSlicer() {}

    public static <T> Page<T> of(List<T> all, Integer page, Integer size) {
        int pageNo = page == null || page < 0 ? 0 : page;
        int pageSize = size == null || size < 1 ? 20 : Math.min(size, MAX_SIZE);

        int from = Math.min(pageNo * pageSize, all.size());
        int to = Math.min(from + pageSize, all.size());

        return new PageImpl<>(all.subList(from, to),
                PageRequest.of(pageNo, pageSize), all.size());
    }
}
