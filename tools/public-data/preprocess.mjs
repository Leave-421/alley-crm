#!/usr/bin/env node
// 공공데이터 기본값 전처리(E2 #16). 원본(약 850MB)은 저장소에 넣지 않고, 업종 단위 작은 CSV 하나만 만든다.
//
// 결정: 기획 저장소 reviews/decision-meeting-2026-11-26.md
//   P-1 매출 비중은 관측값만 저장(요일 · 시간대 · 성별 · 연령대 비중을 따로). 교차값(곱)은 추천 계산(E5) 때 만든다.
//   P-2 대학가 인근 발달상권 여러 곳 합산 — config.json sales.areas
//   P-4 공방 · 소품샵은 매출 행을 만들지 않는다(추천 계산에서 중립값). CLQ는 상가정보 근사 분류로 계산한다.
//
// 사용법 (Node 24, 추가 패키지 없음):
//   node tools/public-data/preprocess.mjs scan  <추정매출 CSV>                 # 참고 상권 후보 훑기
//   node tools/public-data/preprocess.mjs build <추정매출 CSV> <상가정보 서울 CSV> <출력 CSV>
// 원본 받는 곳 · 형식은 README.md.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const config = JSON.parse(fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "config.json"), "utf8"));

/** 따옴표 · 따옴표 안 쉼표를 처리하는 CSV 한 줄 파서. */
export function parseCsvLine(line) {
  const out = [];
  let cur = "";
  let quoted = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') { cur += '"'; i++; }
      else if (ch === '"') quoted = false;
      else cur += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ",") { out.push(cur); cur = ""; }
    else cur += ch;
  }
  out.push(cur);
  return out;
}

function readRows(file, encoding) {
  const text = new TextDecoder(encoding).decode(fs.readFileSync(file));
  const lines = text.split(/\r?\n/).filter((l) => l.length > 0);
  const header = parseCsvLine(lines[0]).map((h) => h.replace(/^﻿/, ""));
  return { header, rows: lines.slice(1).map(parseCsvLine) };
}

function col(header, name) {
  const i = header.indexOf(name);
  if (i < 0) throw new Error(`열이 없습니다: ${name}`);
  return i;
}

// ---- 추정매출: 요일 · 시간대 · 성별 · 연령대 비중, 매력도 ----

const DAYS = [["mon", "월요일"], ["tue", "화요일"], ["wed", "수요일"], ["thu", "목요일"], ["fri", "금요일"], ["sat", "토요일"], ["sun", "일요일"]];
/** 시간대 6구간(CONTRACTS.md 「3」 timeBand). 원본 열 이름은 매출 '금액' 열을 쓴다(건수 열 이름은 원본이 잘못 붙어 있음). */
const TIME_BANDS = [["night", "00~06"], ["morning", "06~11"], ["lunch", "11~14"], ["afternoon", "14~17"], ["evening", "17~21"], ["late", "21~24"]];
/** 연령대 6구간 → 계약 4구간(같은 묶음 안 합산이라 관측값). */
const AGES = [["age_20_or_under", ["10", "20"]], ["age_30s", ["30"]], ["age_40s", ["40"]], ["age_50_or_over", ["50", "60_이상"]]];

function salesCategoryOf(serviceCode) {
  for (const [category, codes] of Object.entries(config.sales.categories)) {
    if (codes.includes(serviceCode)) return category;
  }
  return null;
}

function sumSales(salesCsv) {
  const { header, rows } = readRows(salesCsv, "euc-kr");
  const c = (n) => col(header, n);
  const iQuarter = c("기준_년분기_코드"), iArea = c("상권_코드"), iService = c("서비스_업종_코드"), iTotal = c("당월_매출_금액");
  const days = DAYS.map(([, ko]) => c(`${ko}_매출_금액`));
  const bands = TIME_BANDS.map(([, ko]) => c(`시간대_${ko}_매출_금액`));
  const genders = [c("남성_매출_금액"), c("여성_매출_금액")];
  const ages = AGES.map(([, groups]) => groups.map((g) => c(`연령대_${g}_매출_금액`)));
  const areaCodes = new Set(config.sales.areas.map((a) => a.code));

  const sums = {};
  for (const r of rows) {
    if (r[iQuarter] !== config.sales.quarter || !areaCodes.has(r[iArea])) continue;
    const category = salesCategoryOf(r[iService]);
    if (!category) continue;
    const s = (sums[category] ??= { total: 0, days: Array(7).fill(0), bands: Array(6).fill(0), genders: [0, 0], ages: Array(4).fill(0) });
    s.total += Number(r[iTotal]);
    days.forEach((i, k) => (s.days[k] += Number(r[i])));
    bands.forEach((i, k) => (s.bands[k] += Number(r[i])));
    genders.forEach((i, k) => (s.genders[k] += Number(r[i])));
    ages.forEach((is, k) => is.forEach((i) => (s.ages[k] += Number(r[i]))));
  }
  return sums;
}

function share(values) {
  const total = values.reduce((a, b) => a + b, 0);
  return total > 0 ? values.map((v) => v / total) : null;
}

function salesRows(sums) {
  const out = [];
  const source = config.sales.source;
  const period = config.sales.periodLabel;
  const row = (kind, categoryTo, slot, gender, ageGroup, value) =>
    out.push({ kind, categoryFrom: "", categoryTo, slot, gender, ageGroup, value, source, period });
  const categories = Object.keys(sums).sort();
  const attractiveness = share(categories.map((c) => sums[c].total));
  categories.forEach((c, i) => row("attractiveness", c, "", "", "", attractiveness[i]));
  for (const c of categories) {
    const s = sums[c];
    share(s.days)?.forEach((v, k) => row("day_share", c, DAYS[k][0], "", "", v));
    share(s.bands)?.forEach((v, k) => row("time_band_share", c, TIME_BANDS[k][0], "", "", v));
    const g = share(s.genders);
    if (g) { row("gender_share", c, "", "male", "", g[0]); row("gender_share", c, "", "female", "", g[1]); }
    share(s.ages)?.forEach((v, k) => row("age_share", c, "", "", AGES[k][0], v));
  }
  return out;
}

// ---- 상가정보: 업종 동반 입지(CLQ) ----

function storeCategoryOf(rule, middle, small, name) {
  for (const [category, r] of Object.entries(config.stores.categories)) {
    if (r.middle?.includes(middle)) return category;
    if (r.small?.includes(small)) return category;
    if (r.nameContains?.some((w) => name.includes(w))) return category;
  }
  return null;
}

/**
 * CLQ(A → B) = (A 가게 반경 안 이웃 중 B 비율의 평균) ÷ (전체 가게 중 B 비율). 1보다 크면 A 주변에 B가 우연보다 많다.
 * 이웃은 업종과 상관없이 반경 안의 모든 가게(자기 제외). 서울 전체 가게로 계산한다(조사 P-6).
 */
function clqRows(storesCsv) {
  const { header, rows } = readRows(storesCsv, "utf-8");
  const c = (n) => col(header, n);
  const iName = c("상호명"), iMiddle = c("상권업종중분류코드"), iSmall = c("상권업종소분류코드"), iLon = c("경도"), iLat = c("위도");
  const R = config.stores.radiusMeters;
  const M_PER_LAT = 111_000, M_PER_LON = 88_200; // 서울(위도 37.5도) 근사
  const points = [];
  for (const r of rows) {
    const lon = Number(r[iLon]), lat = Number(r[iLat]);
    if (!Number.isFinite(lon) || !Number.isFinite(lat)) continue;
    points.push({ x: lon * M_PER_LON, y: lat * M_PER_LAT, cat: storeCategoryOf(null, r[iMiddle], r[iSmall], r[iName]) });
  }
  const grid = new Map();
  const key = (gx, gy) => gx + ":" + gy;
  for (const p of points) {
    const k = key(Math.floor(p.x / R), Math.floor(p.y / R));
    (grid.get(k) ?? grid.set(k, []).get(k)).push(p);
  }
  const categories = Object.keys(config.stores.categories).sort();
  const count = Object.fromEntries(categories.map((c) => [c, 0]));
  points.forEach((p) => p.cat && count[p.cat]++);
  const N = points.length;
  const fracSum = {}; // fracSum[A][B]
  const nA = Object.fromEntries(categories.map((c) => [c, 0]));
  for (const p of points) {
    if (!p.cat) continue;
    const gx = Math.floor(p.x / R), gy = Math.floor(p.y / R);
    let neighbors = 0;
    const byCat = {};
    for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) {
      for (const q of grid.get(key(gx + dx, gy + dy)) ?? []) {
        if (q === p || (q.x - p.x) ** 2 + (q.y - p.y) ** 2 > R * R) continue;
        neighbors++;
        if (q.cat) byCat[q.cat] = (byCat[q.cat] ?? 0) + 1;
      }
    }
    if (neighbors === 0) continue;
    nA[p.cat]++;
    const f = (fracSum[p.cat] ??= {});
    for (const b of categories) f[b] = (f[b] ?? 0) + (byCat[b] ?? 0) / neighbors;
  }
  const out = [];
  for (const a of categories) {
    for (const b of categories) {
      if (!nA[a] || !count[b]) continue;
      const expected = (count[b] - (a === b ? 1 : 0)) / (N - 1);
      const value = fracSum[a][b] / nA[a] / expected;
      out.push({ kind: "clq", categoryFrom: a, categoryTo: b, slot: "", gender: "", ageGroup: "", value,
        source: config.stores.source, period: config.stores.periodLabel });
    }
  }
  console.error(`[clq] 서울 가게 ${N}곳, 업종별 ${JSON.stringify(count)}, 반경 ${R}m`);
  return out;
}

// ---- 출력 ----

function writeCsv(file, rows) {
  const esc = (v) => (/[",\n]/.test(String(v)) ? `"${String(v).replace(/"/g, '""')}"` : String(v));
  const lines = ["kind,category_from,category_to,slot,gender,age_group,value,source,period"];
  for (const r of rows) {
    lines.push([r.kind, r.categoryFrom, r.categoryTo, r.slot, r.gender, r.ageGroup, r.value.toFixed(6), r.source, r.period].map(esc).join(","));
  }
  fs.writeFileSync(file, lines.join("\n") + "\n", "utf8");
}

function scan(salesCsv) {
  const { header, rows } = readRows(salesCsv, "euc-kr");
  const c = (n) => col(header, n);
  const iQuarter = c("기준_년분기_코드"), iType = c("상권_구분_코드"), iArea = c("상권_코드"), iAreaName = c("상권_코드_명"),
    iService = c("서비스_업종_코드"), iTotal = c("당월_매출_금액");
  const areas = new Map();
  for (const r of rows) {
    if (r[iQuarter] !== config.sales.quarter || r[iType] !== "D") continue;
    const category = salesCategoryOf(r[iService]);
    const a = areas.get(r[iArea]) ?? areas.set(r[iArea], { name: r[iAreaName], cats: new Set(), total: 0 }).get(r[iArea]);
    if (category) { a.cats.add(category); a.total += Number(r[iTotal]); }
  }
  const all = Object.keys(config.sales.categories);
  [...areas.entries()].sort((x, y) => y[1].cats.size - x[1].cats.size || y[1].total - x[1].total).forEach(([code, a]) =>
    console.log(`${a.cats.size}/${all.length}\t${Math.round(a.total / 1e8)}억\t${code}\t${a.name}\t${all.filter((x) => !a.cats.has(x)).join(",")}`));
}

const [mode, ...args] = process.argv.slice(2);
if (mode === "scan") {
  scan(args[0]);
} else if (mode === "build") {
  const [salesCsv, storesCsv, output] = args;
  const sums = sumSales(salesCsv);
  for (const [category, codes] of Object.entries(config.sales.categories)) {
    if (!sums[category]) console.error(`[sales] 참고 상권에 ${category}(${codes.join(",")}) 매출 행이 없습니다`);
  }
  const rows = [...salesRows(sums), ...clqRows(storesCsv)];
  writeCsv(output, rows);
  console.error(`[build] ${rows.length}행 → ${output}`);
} else {
  console.error("사용법: preprocess.mjs scan <추정매출 CSV> | build <추정매출 CSV> <상가정보 서울 CSV> <출력 CSV>");
  process.exit(1);
}
