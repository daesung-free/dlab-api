package com.dlab.domain.vocab.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 단어 뜻 → 정답 후보 (D:VOCA 개발문서 v2 5.2).
 *
 * <h2>★★ 왜 필요한가</h2>
 * 원고 2,000개 중 <b>1,461개(73%)가 한 칸에 여러 뜻을 담고 있다.</b>
 * {@code financial = 재정의, 금융의} 에 학생이 「금융의」라고 쓰면 <b>맞는 답인데
 * 원문 완전일치로는 오답이 된다.</b> 그러면 관리자가 2,000개마다 인정 표현을 손으로
 * 넣어야 한다.
 *
 * <h2>규칙 (순서가 중요하다)</h2>
 * <ol>
 *   <li>괄호 {@code ( )} 안을 <b>먼저 보호한다</b></li>
 *   <li>세미콜론으로 나눈다 — 품사가 갈리는 경계</li>
 *   <li>쉼표로 나눈다 — 같은 품사 안의 유의어</li>
 *   <li>괄호를 <b>뺀 것과 안 뺀 것 둘 다</b> 후보로 넣는다 — 괄호는 문맥 설명이다</li>
 *   <li>대괄호 {@code [ ]} 는 바꿔 쓸 수 있는 말 — <b>양쪽 다</b> 넣는다</li>
 *   <li>물결 {@code ~} 제거, 공백 정리, 끝 문장부호 제거</li>
 * </ol>
 *
 * <h2>⚠️ 1번을 빼면 조용히 틀린다</h2>
 * {@code (온도, 각도의) 도} 는 <b>괄호 안에 쉼표가 있다.</b> 보호하지 않고 쉼표로 먼저
 * 나누면 {@code (온도} / {@code 각도의) 도} 로 쪼개진다 — 둘 다 뜻이 아니고, 학생이
 * 「도」라고 써도 오답이 된다.
 *
 * <p>실측(원고 2,000개): 쉼표 1,262건 · 세미콜론 376건 · 괄호 198건 · 대괄호 227건 ·
 * 물결 58건.
 *
 * <h2>★ 같은 규칙을 학생 답에도 쓴다</h2>
 * 정답 후보를 만드는 쪽과 학생 답을 다듬는 쪽이 다르면 <b>같은 글자인데 안 맞는</b>
 * 경우가 생긴다. {@link #normalize(String)} 가 그 자리다.
 */
public final class MeaningSplitter {

    private static final Pattern PAREN = Pattern.compile("\\([^)]*\\)");
    private static final Pattern BRACKET =
            Pattern.compile("^(.*?)([^\\[\\]]*)\\[([^\\[\\]]+)\\](.*)$");
    private static final Pattern SPACES = Pattern.compile("\\s+");

    private MeaningSplitter() {
    }

    /**
     * 원고의 뜻 한 칸을 정답 후보 목록으로 쪼갠다.
     *
     * @return 입력 순서를 유지한 중복 없는 목록. 입력이 비면 빈 목록
     */
    public static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        Set<String> result = new LinkedHashSet<>();
        for (String piece : splitTopLevel(raw)) {
            // 괄호를 뺀 것도 후보다 — 괄호는 문맥 설명이라 학생이 안 쓴다
            for (String withOrWithout : List.of(piece, PAREN.matcher(piece).replaceAll(""))) {
                for (String expanded : expandBracket(withOrWithout)) {
                    String cleaned = clean(expanded);
                    if (!cleaned.isBlank()) {
                        result.add(cleaned);
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /**
     * {@code ;} 와 {@code ,} 로 나누되 <b>괄호·대괄호 안은 건드리지 않는다.</b>
     *
     * <h2>⚠️ 깊이를 세지 않으면 조용히 틀린다</h2>
     * {@code (온도, 각도의) 도} 는 <b>괄호 안에 쉼표가 있다.</b> 그냥 쉼표로 나누면
     * {@code (온도} / {@code 각도의) 도} 가 된다 — 둘 다 뜻이 아니고, 학생이 「도」라고
     * 써도 오답이 된다.
     *
     * <p>자리표시자로 치환해 두고 나중에 되돌리는 방법도 있지만, 되돌리기가 한 군데라도
     * 어긋나면 <b>자리표시자가 그대로 정답 후보에 들어간다</b>(실제로 그렇게 당했다).
     * 깊이를 세는 쪽이 되돌릴 것이 없어 안전하다.
     */
    private static List<String> splitTopLevel(String raw) {
        List<String> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (char c : raw.toCharArray()) {
            if (c == '(' || c == '[') {
                depth++;
            } else if (c == ')' || c == ']') {
                // ⚠️ 닫는 괄호가 더 많은 원고가 있어도 음수로 내려가지 않게 한다 —
                //    내려가면 그 뒤 쉼표가 전부 「괄호 안」으로 취급돼 쪼개지지 않는다
                depth = Math.max(0, depth - 1);
            } else if ((c == ';' || c == ',') && depth == 0) {
                pieces.add(current.toString().trim());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        pieces.add(current.toString().trim());
        pieces.removeIf(String::isBlank);
        return pieces;
    }

    /**
     * 학생 답 다듬기 — <b>쪼개지 않는다.</b>
     *
     * <p>학생이 「재정의, 금융의」처럼 두 개를 쓰는 경우가 있지만, 그걸 쪼개서 하나만
     * 맞으면 정답으로 하면 <b>아무거나 여러 개 써서 맞히는</b> 것이 된다. 공백·부호만
     * 정리해 후보와 대조한다.
     */
    public static String normalize(String answer) {
        return answer == null ? "" : clean(answer);
    }

    /** {@code 실시[수행]하다} → {@code 실시하다} · {@code 수행하다} */
    private static Set<String> expandBracket(String value) {
        Matcher m = BRACKET.matcher(value);
        if (!m.matches()) {
            return Set.of(value);
        }
        String head = m.group(1);
        String before = m.group(2);
        String alternative = m.group(3);
        String tail = m.group(4);
        // ★ 중첩 대괄호가 또 있으면 재귀로 푼다 — 원고에 둘 이상 들어간 칸이 있다
        Set<String> out = new LinkedHashSet<>();
        out.addAll(expandBracket(head + before + tail));
        out.addAll(expandBracket(head + alternative + tail));
        return out;
    }

    private static String clean(String value) {
        String cleaned = value.replace("~", "");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").trim();
        while (!cleaned.isEmpty() && ".。,;·".indexOf(cleaned.charAt(cleaned.length() - 1)) >= 0) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
