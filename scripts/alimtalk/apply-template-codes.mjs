#!/usr/bin/env node
/**
 * 카카오 심사를 통과한 알림톡 템플릿 코드를 서버에 넣는다.
 *
 * ★★ 이것을 하지 않으면 승인돼도 발송되지 않는다.
 *   NotificationService.dispatch 가 kakao_template_code 가 비면
 *   SKIPPED「알림톡 템플릿 코드 미등록」으로 남긴다.
 *
 * ★ 두 단계를 타야 한다. 한 단계만 하면 담당자가 화면에서 결과를 기록할 수 없다.
 *     POST /{id}/review/submit   { kakaoTemplateCode }   → SUBMITTED
 *     POST /{id}/review/result   { approved, note }      → APPROVED
 *
 * ⚠️ 기본이 미리보기다. --apply 를 줘야 쓴다.
 * ⚠️ 이미 APPROVED 이고 코드가 같으면 건너뛴다(멱등).
 *
 * 사용:
 *   ADMIN_TOKEN=<SUPER_ADMIN Access Token> BASE_URL=https://api.d-dlab.link \
 *     node scripts/alimtalk/apply-template-codes.mjs [--apply]
 */

// 카카오 승인분 ↔ 서버 이벤트. ★ 반려된 것은 넣지 않는다.
const CODES = {
  ATTENDANCE_CHECK_IN: 'UM_8230',
  ATTENDANCE_CHECK_OUT: 'UM_8231',
  ATTENDANCE_EARLY_LEAVE: 'UM_8232',
  ATTENDANCE_LATE: 'UM_8233',
  ATTENDANCE_OUTING: 'UM_8234',
  ATTENDANCE_RETURN: 'UM_8235',
  MISSING_ATTENDANCE: 'UM_8236',
  MEAL_APPLY_DEADLINE: 'UM_8237',
  TUITION_UNPAID_NOTICE: 'UM_8239',
  APPROVAL_REQUEST_CREATED: 'UM_8240',
  APPROVAL_HANDED_OVER: 'UM_8241',
  APPROVAL_APPROVED_AFTER_TIMEOUT: 'UM_8242',
  APPROVAL_APPROVED_BEFORE_TIMEOUT: 'UM_8243',
};

// ⚠️ 넣지 않는 것 — 이유를 남겨 둔다
const SKIPPED = {
  UM_8514: '등록비 안내 — 카카오 반려(REJ). 수신 근거를 문구에 넣어 재심사해야 한다',
  UM_8513: '대기자 등록 안내 — 승인됐으나 서버에 대응 템플릿 행이 없다(대상 선택 축 미구현)',
};

const APPLY = process.argv.includes('--apply');
const BASE = (process.env.BASE_URL || '').replace(/\/$/, '');
const TOKEN = process.env.ADMIN_TOKEN;

if (!BASE || !TOKEN) {
  console.error('BASE_URL 과 ADMIN_TOKEN 이 필요합니다.');
  process.exit(1);
}

const headers = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };

async function call(path, init) {
  const res = await fetch(BASE + path, { ...init, headers });
  const body = await res.text();
  let json;
  try { json = JSON.parse(body); } catch { json = null; }
  if (!res.ok) {
    throw new Error(`${res.status} ${json?.error?.message ?? body.slice(0, 160)}`);
  }
  return json?.data;
}

const templates = await call('/api/v1/admin/notification-templates', { method: 'GET' });
const byEvent = new Map(templates.map((t) => [t.event, t]));

let applied = 0;
let skipped = 0;
const problems = [];

for (const [event, code] of Object.entries(CODES)) {
  const t = byEvent.get(event);
  if (!t) {
    problems.push(`${event}: 서버에 템플릿이 없다 (코드 ${code})`);
    continue;
  }
  if (t.reviewStatus === 'APPROVED' && t.kakaoTemplateCode === code) {
    console.log(`  ${event.padEnd(34)} 이미 APPROVED (${code}) — 건너뜀`);
    skipped++;
    continue;
  }
  console.log(`  ${event.padEnd(34)} ${t.reviewStatus} → APPROVED (${code})`);
  if (!APPLY) continue;
  try {
    await call(`/api/v1/admin/notification-templates/${t.id}/review/submit`, {
      method: 'POST', body: JSON.stringify({ kakaoTemplateCode: code }),
    });
    await call(`/api/v1/admin/notification-templates/${t.id}/review/result`, {
      method: 'POST',
      body: JSON.stringify({ approved: true, note: '카카오 승인 2026-10-23' }),
    });
    applied++;
  } catch (e) {
    problems.push(`${event}: ${e.message}`);
  }
}

console.log('\n넣지 않는 것');
for (const [code, why] of Object.entries(SKIPPED)) console.log(`  ${code}  ${why}`);

console.log(`\n대상 ${Object.keys(CODES).length}건 · 적용 ${applied} · 건너뜀 ${skipped}`);
if (problems.length) {
  console.log('\n★ 확인할 것');
  for (const p of problems) console.log('  ' + p);
  process.exit(1);
}
if (!APPLY) console.log('\n미리보기입니다. 실제로 넣으려면 --apply 를 주세요.');
