#!/usr/bin/env node
//
// 전 지점 좌석 2,140석 적재 — 운영 API 호출 방식.
//
// ★ 왜 마이그레이션(SQL)이 아닌가
//   `seat_master.kiosk_seat_cd` 가 **등록 시점에 계산돼 저장된다**(동탄 별관 1번 → 1001번,
//   `KioskCodeTranslator`). SQL 로 밀어 넣으면 그 변환이 돌지 않아 **단말에 내려가는
//   번호가 어긋난다** — 화면과 DB 는 멀쩡해서 원인을 찾기가 매우 어렵다.
//   지점 안 키오스크 번호 충돌 검사도 서비스에만 있다. 그래서 반드시 API 로 넣는다.
//
// ★ 기본은 미리보기다. 2,140석은 잘못 들어가면 되돌리기가 번거롭다.
//
//   모드
//     --plan     오프라인. 서버·토큰 없이 JSON 만으로 계획과 검산표를 출력한다
//     (기본)     --dry-run. 서버에 **조회만** 해서 무엇을 만들고 무엇을 건너뛸지 본다
//     --apply    실제로 만든다
//
//   사용
//     node scripts/seat-layout/load-seat-layout.mjs --plan
//     ADMIN_TOKEN=... BASE_URL=https://... node scripts/seat-layout/load-seat-layout.mjs
//     ADMIN_TOKEN=... BASE_URL=https://... node scripts/seat-layout/load-seat-layout.mjs --apply
//     ... --branch 분당            한 지점만
//
//   ⚠️ 토큰은 환경변수로만 받는다. 스크립트·커밋에 넣지 말 것(CLAUDE.md §8).
//   ⚠️ 권한은 `SUPER_ADMIN` 이 필요하다 — 11개 지점을 한 번에 돌리므로 본사 계정이어야 한다.
//
// 멱등하다: 이미 있는 구역·좌석은 건너뛰고 몇 건을 건너뛰었는지 보고한다.
// 한 구역씩 처리하고 실패하면 그 구역에서 멈춘다 — 절반 들어간 상태를 만들지 않는다.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const DATA = join(HERE, 'seat-layout.json');

const BASE_URL = (process.env.BASE_URL || 'http://localhost:8080').replace(/\/+$/, '');
const TOKEN = process.env.ADMIN_TOKEN;

const argv = process.argv.slice(2);
const MODE = argv.includes('--apply') ? 'apply' : argv.includes('--plan') ? 'plan' : 'dry-run';
const ONLY_BRANCH = valueOf('--branch');

function valueOf(flag) {
  const i = argv.indexOf(flag);
  return i >= 0 ? argv[i + 1] : null;
}

// ★ 0-1 집계표 원문. **JSON 과 따로 들고 있다** — 데이터 파일을 고치면 그 안의 합계도
//   같이 바뀌어 자기 자신과는 언제나 맞는다. 바깥에 둬야 검산이 검산이 된다.
const BRANCH_EXPECTED = {
  분당: 181, 일산: 138, '동탄 본관': 161, '동탄 별관': 100, 김포: 116, 부천: 162,
  이매: 134, 광명: 196, 목동: 292, 송파: 199, 대전: 203, 대구: 258,
};
const STYLE_EXPECTED = { CLASSROOM: 1637, FOCUS: 449, SINGLE_ROOM: 54 };
const TOTAL_EXPECTED = 2140;

const STYLE_NAME = { CLASSROOM: '교실형', FOCUS: '집중형', SINGLE_ROOM: '1인실' };

// ── HTTP ────────────────────────────────────────────────────────────────────

async function api(method, path, body) {
  const res = await fetch(`${BASE_URL}/api/v1/admin${path}`, {
    method,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* 본문이 JSON 이 아닐 수 있다 */ }
  if (!res.ok || (json && json.success === false)) {
    const reason = json?.error?.message || json?.message || text || res.statusText;
    throw new Error(`${method} ${path} → ${res.status} ${reason}`);
  }
  return json?.data;
}

// ── 좌석번호 계산 — 서버 `SeatMasterAdminService.plan()` 과 같아야 한다 ────────
//
// ★ 역방향은 좌표가 아니라 **번호 붙는 순서**를 뒤집는다.
//   여기서 어긋나면 "이미 있는지" 판정이 틀려 멱등성이 깨진다.

function seatCodesOf(grid) {
  const skips = new Set((grid.skips || []).map((s) => `${s.row}:${s.column}`));
  const rows = range(grid.rows, grid.reverseY);
  const columns = range(grid.columns, grid.reverseX);
  const outer = grid.columnMajor ? columns : rows;
  const inner = grid.columnMajor ? rows : columns;

  const codes = [];
  let number = grid.startNumber;
  for (const o of outer) {
    for (const i of inner) {
      const row = grid.columnMajor ? i : o;
      const column = grid.columnMajor ? o : i;
      if (skips.has(`${row}:${column}`)) continue;
      codes.push(String(number++).padStart(grid.numberPadding || 0, '0'));
    }
  }
  return codes;
}

function range(size, reverse) {
  const out = [];
  for (let i = 1; i <= size; i++) out.push(reverse ? size - i + 1 : i);
  return out;
}

// ── 적재 ────────────────────────────────────────────────────────────────────

const label = (a) => `${a.academyName}${a.building === 'ANNEX' ? ' 별관' : a.building === 'MAIN' && a.academyName === '동탄' ? ' 본관' : ''} ${a.areaCd}`;
const branchKey = (a) => (a.academyName === '동탄'
  ? (a.building === 'ANNEX' ? '동탄 별관' : '동탄 본관') : a.academyName);

async function resolveAcademy(cache, acadCd, name) {
  if (!cache.academies) {
    cache.academies = new Map(
      (await api('GET', '/academies?includeInactive=true')).map((a) => [a.acadCd, a]));
  }
  const found = cache.academies.get(acadCd);
  if (!found) throw new Error(`지점을 찾을 수 없습니다: ${name}(acad_cd=${acadCd})`);
  return found;
}

async function resolveBuilding(cache, academyId, kind, name) {
  if (!cache.buildings.has(academyId)) {
    cache.buildings.set(academyId, await api('GET', `/seats/buildings?academyId=${academyId}`));
  }
  const list = cache.buildings.get(academyId);
  // ★ 코드로 가르지 않는다. 운영 별관 코드가 지점마다 다르다
  //   (동탄 '2' · 대구 'SUB' — Building.isAnnex() 주석). `main` 플래그로 가른다.
  const found = kind === 'ANNEX' ? list.find((b) => !b.main) : list.find((b) => b.main);
  if (!found) {
    throw new Error(kind === 'ANNEX'
      ? `${name} 별관(관) 행이 없습니다. 운영에 이미 있어야 하는 값이라 이 스크립트는 만들지 않습니다 `
        + `— seat_cd_offset 은 만든 뒤 바꿀 수 없어 잘못 만들면 키오스크 번호가 영구히 어긋납니다.`
      : `${name} 본관(관) 행이 없습니다.`);
  }
  if (kind === 'ANNEX' && found.seatCdOffset !== 1000) {
    throw new Error(`${name} 별관 seat_cd_offset 이 1000 이 아닙니다(${found.seatCdOffset}). `
      + `배치표가 1000번대라 그대로 넣으면 단말 번호가 어긋납니다.`);
  }
  return found;
}

async function run() {
  const data = JSON.parse(readFileSync(DATA, 'utf8'));
  let areas = data.areas;
  if (ONLY_BRANCH) {
    areas = areas.filter((a) => a.academyName === ONLY_BRANCH
      || branchKey(a) === ONLY_BRANCH);
    if (!areas.length) throw new Error(`그런 지점이 없습니다: ${ONLY_BRANCH}`);
  }

  console.log(`모드: ${MODE === 'apply' ? '★ 실제 적재(--apply)'
    : MODE === 'plan' ? '계획만(오프라인)' : '미리보기(조회만)'}`);
  if (MODE !== 'plan') console.log(`서버: ${BASE_URL}`);
  console.log(`대상: 구역 ${areas.length}개 · 좌석 ${areas.reduce((s, a) => s + a.expectedSeats, 0)}석\n`);

  selfCheck(data, areas.length === data.areas.length);
  if (MODE === 'plan') { planOnly(areas); return; }

  const cache = { academies: null, buildings: new Map() };
  const report = [];

  for (const area of areas) {
    const name = label(area);
    const academy = await resolveAcademy(cache, area.acadCd, area.academyName);
    const building = await resolveBuilding(cache, academy.id, area.building, area.academyName);

    // 1) 구역 — 없을 때만 만든다
    const existingAreas = await api('GET',
      `/seats/areas?academyId=${academy.id}&buildingId=${building.id}&includeInactive=true`);
    let studyArea = existingAreas.find((a) => a.areaCd === area.areaCd);
    let areaAction = '있음';
    if (!studyArea) {
      areaAction = MODE === 'apply' ? '생성' : '생성 예정';
      if (MODE === 'apply') {
        studyArea = await api('POST', '/seats/areas', {
          academyId: academy.id,
          buildingId: building.id,
          areaCd: area.areaCd,
          areaNm: area.areaNm,
          sortOrder: area.sortOrder,
          areaType: 'STUDY',
        });
      }
    }

    // 2) 좌석 — 격자 단위로 이미 있는지 본다
    const existing = studyArea
      ? new Set((await api('GET', `/seats/masters?studyAreaId=${studyArea.id}`))
        .map((s) => s.seatCd))
      : new Set();

    let created = 0; let skipped = 0; const todo = [];
    for (const [idx, grid] of area.grids.entries()) {
      const codes = seatCodesOf(grid);
      const have = codes.filter((c) => existing.has(c)).length;
      if (have === codes.length && codes.length > 0) { skipped += codes.length; continue; }
      if (have > 0) {
        throw new Error(`${name} 격자 ${idx + 1}이 ${have}/${codes.length}석만 들어가 있습니다. `
          + `반쯤 들어간 상태라 자동으로 메우지 않습니다 — 구역을 비우고 다시 돌리거나 `
          + `직접 확인하세요.`);
      }
      todo.push({ idx, grid, codes });
    }

    for (const { idx, grid, codes } of todo) {
      if (MODE === 'apply') {
        await api('POST', '/seats/masters/grid', {
          studyAreaId: studyArea.id,
          rows: grid.rows,
          columns: grid.columns,
          startNumber: grid.startNumber,
          numberPadding: grid.numberPadding,
          startX: grid.startX,
          startY: grid.startY,
          columnMajor: grid.columnMajor,
          reverseX: grid.reverseX,
          reverseY: grid.reverseY,
          skips: grid.skips || [],
          styleRanges: area.styleRanges,
        });
      }
      created += codes.length;
      void idx;
    }

    report.push({ area, name, areaAction, created, skipped, studyArea });
    console.log(`${name.padEnd(12)} 구역 ${areaAction} · 좌석 `
      + `${MODE === 'apply' ? '생성' : '생성 예정'} ${String(created).padStart(3)}석`
      + ` · 건너뜀 ${String(skipped).padStart(3)}석`);
  }

  console.log('');
  await verify(report);
}

/** 데이터 자체 검산 — 서버가 없어도 돈다. */
function selfCheck(data, whole) {
  const byBranch = {}; const byStyle = {}; let total = 0;
  for (const a of data.areas) {
    const sum = Object.values(a.styleCounts).reduce((x, y) => x + y, 0);
    if (sum !== a.expectedSeats) {
      throw new Error(`${label(a)} 좌석형태 합계가 좌석 수와 다릅니다: ${sum} vs ${a.expectedSeats}`);
    }
    const planned = a.grids.reduce((s, g) => s + seatCodesOf(g).length, 0);
    if (planned !== a.expectedSeats) {
      throw new Error(`${label(a)} 격자가 만들 좌석 수가 다릅니다: ${planned} vs ${a.expectedSeats}`);
    }
    const codes = a.grids.flatMap(seatCodesOf);
    if (new Set(codes).size !== codes.length) {
      throw new Error(`${label(a)} 좌석번호가 구역 안에서 겹칩니다.`);
    }
    byBranch[branchKey(a)] = (byBranch[branchKey(a)] || 0) + a.expectedSeats;
    for (const [s, n] of Object.entries(a.styleCounts)) byStyle[s] = (byStyle[s] || 0) + n;
    total += a.expectedSeats;
  }
  if (!whole) { console.log('(일부 지점만 돌리므로 전체 집계 검산은 건너뜁니다)\n'); return; }

  const bad = [];
  for (const [b, n] of Object.entries(BRANCH_EXPECTED)) {
    if (byBranch[b] !== n) bad.push(`${b} ${byBranch[b]} ≠ ${n}`);
  }
  for (const [s, n] of Object.entries(STYLE_EXPECTED)) {
    if (byStyle[s] !== n) bad.push(`${STYLE_NAME[s]} ${byStyle[s]} ≠ ${n}`);
  }
  if (total !== TOTAL_EXPECTED) bad.push(`합계 ${total} ≠ ${TOTAL_EXPECTED}`);
  if (bad.length) throw new Error(`데이터가 0-1 집계표와 다릅니다: ${bad.join(' · ')}`);
  console.log('데이터 검산: 0-1 집계표와 일치 '
    + `(합계 ${total} · 교실형 ${byStyle.CLASSROOM} · 집중형 ${byStyle.FOCUS} · 1인실 ${byStyle.SINGLE_ROOM})\n`);
}

function planOnly(areas) {
  console.log('구역         좌석  격자  형태');
  console.log('─'.repeat(64));
  for (const a of areas) {
    const styles = Object.entries(a.styleCounts)
      .map(([s, n]) => `${STYLE_NAME[s]} ${n}`).join(' · ');
    console.log(`${label(a).padEnd(12)} ${String(a.expectedSeats).padStart(4)}  `
      + `${String(a.grids.length).padStart(3)}   ${styles}`);
  }
  console.log('─'.repeat(64));
  console.log(`계 ${areas.reduce((s, a) => s + a.expectedSeats, 0)}석\n`);
  console.log('실제로 넣으려면 ADMIN_TOKEN · BASE_URL 을 주고 --apply 로 다시 돌리세요.');
}

/**
 * ★ 검산 — 합계만 맞추지 않는다.
 *
 * 구역별·형태별로 맞아야 한다. 합계가 맞는데 구역이 어긋나는 경우가 실제로 생긴다
 * (한 구역을 덜 넣고 다른 구역을 더 넣어도 2,140 은 그대로다).
 */
async function verify(report) {
  if (MODE !== 'apply') {
    console.log('미리보기라 검산은 적재 뒤에 합니다(--apply).');
    return;
  }
  console.log('검산 — 구역별 좌석 수 · 형태');
  console.log('─'.repeat(72));
  const bad = [];
  let total = 0; const byStyle = {}; const byBranch = {};

  for (const { area, name, studyArea } of report) {
    const cells = await api('GET', `/seats/layout?studyAreaId=${studyArea.id}`);
    const counts = {};
    for (const c of cells) counts[c.seatStyle] = (counts[c.seatStyle] || 0) + 1;

    const okCount = cells.length === area.expectedSeats;
    const okStyle = Object.keys({ ...counts, ...area.styleCounts })
      .every((s) => (counts[s] || 0) === (area.styleCounts[s] || 0));
    if (!okCount || !okStyle) {
      bad.push(`${name}: 좌석 ${cells.length}/${area.expectedSeats} · `
        + `형태 ${JSON.stringify(counts)} ≠ ${JSON.stringify(area.styleCounts)}`);
    }
    total += cells.length;
    for (const [s, n] of Object.entries(counts)) byStyle[s] = (byStyle[s] || 0) + n;
    byBranch[branchKey(area)] = (byBranch[branchKey(area)] || 0) + cells.length;

    console.log(`${name.padEnd(12)} ${String(cells.length).padStart(4)}/${area.expectedSeats}  `
      + `${okCount && okStyle ? 'OK' : '★ 어긋남'}`);
  }

  console.log('─'.repeat(72));
  for (const [b, n] of Object.entries(byBranch)) {
    const want = BRANCH_EXPECTED[b];
    console.log(`${b.padEnd(10)} ${String(n).padStart(4)}/${want} ${n === want ? 'OK' : '★ 어긋남'}`);
    if (n !== want) bad.push(`${b} 지점 합계 ${n} ≠ ${want}`);
  }
  // ★ 일부 지점만 돌렸으면 전체 합계와 대조하지 않는다 — 언제나 모자라 보여
  //   「검산 실패」로 읽히고, 진짜 어긋남을 가린다.
  if (ONLY_BRANCH) {
    console.log(`합계 ${total}석 (일부 지점만 돌려 전체 2,140 과는 대조하지 않습니다)`);
  } else {
    console.log(`합계 ${total}/${TOTAL_EXPECTED} · `
      + Object.entries(STYLE_EXPECTED)
        .map(([s, n]) => `${STYLE_NAME[s]} ${byStyle[s] || 0}/${n}`).join(' · '));
    if (total !== TOTAL_EXPECTED) bad.push(`합계 ${total} ≠ ${TOTAL_EXPECTED}`);
    for (const [s, n] of Object.entries(STYLE_EXPECTED)) {
      if ((byStyle[s] || 0) !== n) bad.push(`${STYLE_NAME[s]} ${byStyle[s] || 0} ≠ ${n}`);
    }
  }

  if (bad.length) {
    console.error('\n★ 검산 실패\n  ' + bad.join('\n  '));
    process.exitCode = 1;
  } else {
    console.log('\n검산 통과 — 구역별·형태별·지점별·합계가 모두 집계표와 일치합니다.');
  }
}

if (MODE !== 'plan' && !TOKEN) {
  console.error('ADMIN_TOKEN 이 없습니다. 환경변수로 주세요(스크립트에 박지 마세요).');
  console.error('  ADMIN_TOKEN=... BASE_URL=https://... node scripts/seat-layout/load-seat-layout.mjs');
  console.error('서버 없이 계획만 보려면 --plan 을 쓰세요.');
  process.exit(1);
}

run().catch((e) => {
  console.error(`\n★ 중단: ${e.message}`);
  if (MODE === 'apply') {
    console.error('  이 구역에서 멈췄습니다. 앞 구역은 들어갔고 뒤 구역은 손대지 않았습니다.');
    console.error('  고친 뒤 그대로 다시 돌리면 됩니다 — 이미 들어간 구역은 건너뜁니다.');
  }
  process.exit(1);
});
