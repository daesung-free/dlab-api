// D:VOCA 응시·제출 부하 테스트 (v1 9장).
//
//   "재원생은 2,000명에서 5,000명 이상까지 늘 수 있고, 최대 50%가 동시 접속할 수 있다
//    (약 2,500명). 시험 시작과 제출 API에 부하 테스트를 한다.
//    서버는 업체가 준비하므로 성능은 그 서버 사양에 따른다."
//
// ★★ 이 스크립트는 「운영 사양에서」 돌려야 의미가 있다.
//    개발 서버에서 2,500 VU 를 걸어 나온 숫자는 운영 성능이 아니다. 인프라가
//    개발용 서버를 그대로 운영으로 전환하는 방식이라(CLAUDE.md §3) 전환 시점에
//    돌리는 것이 맞다.
//
// ★ 쿼리 수는 이미 테스트로 고정해 뒀다 — VocabExamQueryCountTest.
//   문항당 쿼리 증가가 1(원장 쓰기)이고, 조회가 섞이면 CI 가 잡는다.
//   ⚠️ 부하를 걸기 전에 그걸 먼저 한 이유: 문항마다 조회가 도는 구조면 서버를 키워도
//      같은 비율로 늘어나 사양으로 덮을 수 없다.
//
// 실행
//   k6 run -e BASE_URL=https://... -e TOKENS=tokens.json scripts/load/dvoca-exam.js
//
// ⚠️ TOKENS 는 **테스트 계정의** Access Token 목록이다. 실제 학생 토큰을 쓰지 말 것 —
//    응시 기록이 영구 보존되므로(v1 9장) 그 학생 기록에 부하 데이터가 섞인다.

import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// ★ 토큰을 VU 마다 나눠 쓴다. 한 계정으로 2,500 VU 를 돌리면
//   「진행 중인 응시 재사용」에 걸려 전원이 같은 응시를 공유한다 —
//   그러면 start 가 문항을 만들지 않으므로 **측정하려던 경로가 돌지 않는다.**
const tokens = JSON.parse(open(__ENV.TOKENS || './tokens.json'));

const startDuration = new Trend('dvoca_start_duration', true);
const submitDuration = new Trend('dvoca_submit_duration', true);
const startFailed = new Rate('dvoca_start_failed');
const submitFailed = new Rate('dvoca_submit_failed');

export const options = {
    scenarios: {
        // 실제 시험은 「회차가 열리는 시각에 한꺼번에」 몰린다 — 완만히 올리는
        // ramping 보다 이 모양이 현실에 가깝다
        exam_rush: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 500 },
                { duration: '1m', target: 2500 },   // v1 9장: 재원생 5,000 의 50%
                { duration: '3m', target: 2500 },
                { duration: '30s', target: 0 },
            ],
            gracefulRampDown: '30s',
        },
    },
    thresholds: {
        // ⚠️ 이 값은 **합의된 기준이 아니다.** 운영 사양이 정해지지 않았고
        //    v1 9장도 *"성능은 그 서버 사양에 따른다"* 로 둔다.
        //    여기 숫자는 「이보다 느리면 학생이 기다린다」는 체감 기준이다.
        'dvoca_start_duration': ['p(95)<3000'],
        'dvoca_submit_duration': ['p(95)<3000'],
        'dvoca_start_failed': ['rate<0.01'],
        'dvoca_submit_failed': ['rate<0.01'],
    },
};

function authHeaders() {
    const token = tokens[__VU % tokens.length];
    return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } };
}

export default function () {
    const auth = authHeaders();
    let roundId;
    let attemptId;
    let questions = [];

    group('회차 목록', () => {
        const res = http.get(`${BASE_URL}/api/v1/app/vocab/rounds`, auth);
        check(res, { '회차 목록 200': (r) => r.status === 200 });
        if (res.status !== 200) {
            return;
        }
        const rounds = res.json('data') || [];
        // 아직 안 본 회차를 고른다 — 이미 응시한 회차를 다시 시작하면
        // 재응시 경로라 측정 대상이 달라진다
        const target = rounds.find((r) => !r.attempted) || rounds[rounds.length - 1];
        roundId = target && target.roundId;
    });

    if (!roundId) {
        return;
    }

    group('응시 시작', () => {
        const res = http.post(
            `${BASE_URL}/api/v1/app/vocab/rounds/${roundId}/attempts`, null, auth);
        startDuration.add(res.timings.duration);
        const ok = check(res, {
            '시작 200': (r) => r.status === 200,
            // ★ 정답이 새지 않는지 부하 중에도 본다 — 응답 모양이 바뀌면 여기서 걸린다
            '정답 미노출': (r) => {
                if (r.status !== 200) return true;
                const body = r.body || '';
                return !body.includes('answerIndex') && !body.includes('"correct"');
            },
        });
        startFailed.add(!ok);
        if (res.status === 200) {
            attemptId = res.json('data.attemptId');
            questions = res.json('data.questions') || [];
        }
    });

    if (!attemptId) {
        return;
    }

    // 학생이 문제를 푸는 시간. 0 으로 두면 start 직후 submit 이 몰려
    // 실제보다 가혹한 모양이 된다
    sleep(Math.random() * 3 + 1);

    group('제출', () => {
        const answers = questions.map((q) => ({
            seqNo: q.seqNo,
            // 객관식은 보기 중 하나, 주관식은 아무 글자.
            // ⚠️ 정답률을 측정하는 것이 아니라 **채점 경로의 비용**을 재는 것이다
            given: q.type === 'MC' && q.choices.length > 0 ? q.choices[0] : '부하',
        }));
        const res = http.post(
            `${BASE_URL}/api/v1/app/vocab/attempts/${attemptId}/submit`,
            JSON.stringify({ answers }), auth);
        submitDuration.add(res.timings.duration);
        submitFailed.add(!check(res, { '제출 200': (r) => r.status === 200 }));
    });
}
