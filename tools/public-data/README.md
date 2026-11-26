# 공공데이터 기본값 전처리 (E2 #16)

추천 기본값 점수(기획 저장소 `05-policy.md` 「다음 방문 추천」 2단계)에 쓰는 공공데이터를 원본에서 업종 단위로 줄여
`backend/src/main/resources/seed/public-stats.csv`를 만든다. 시드 실행기 ④단계(`PublicStatSeeder`)가 이 파일을 표 `public_stat`에 넣는다.

- 결정: 기획 저장소 `reviews/decision-meeting-2026-11-26.md` (P-1 독립 가정 · 관측 비중만 저장, P-2 대학가 발달상권 합산, P-4 공방 · 소품샵)
- 조사: 기획 저장소 `research/공공데이터 원천과 업종 대응.md`
- **원본(약 850MB)은 저장소에 넣지 않는다.** 아래에서 다시 받는다. 결과 CSV(수 KB)만 커밋한다.
- 참고 상권 이름 · 코드는 `config.json`에만 둔다(P-10). 기획 문서 · 화면에는 쓰지 않는다.

## 원본 받기

| 원본 | 받는 곳 | 쓰는 파일 |
| --- | --- | --- |
| 서울시 상권분석서비스 추정매출-상권 (OA-15572) | https://data.seoul.go.kr/dataList/OA-15572/S/1/datasetView.do 「파일」 탭 → `2025년.zip` | 압축 안 CSV (EUC-KR, 55열) |
| 소상공인시장진흥공단 상가(상권)정보 (15083033) | https://www.data.go.kr/data/15083033/fileData.do → 전국 zip(약 350MB, `_20260930`) | 압축 안 `…_서울_202609.csv` (UTF-8, 39열) |

둘 다 로그인 없이 받을 수 있고 이용허락범위 제한이 없다. 화면 출처 표시는 자료 이름 · 기준 기간까지만 쓴다(NFR-10 #2).

## 실행 (Node 24, 추가 패키지 없음)

```powershell
# 참고 상권 후보 훑기: 기준 분기 발달상권별 우리 업종(대응 가능한 6개) 매출 행 수
node tools/public-data/preprocess.mjs scan "<추정매출 CSV>"

# 결과 파일 만들기 (상가정보 서울 파일이 커서 메모리를 넉넉히)
node --max-old-space-size=6144 tools/public-data/preprocess.mjs build "<추정매출 CSV>" "<상가정보 서울 CSV>" backend/src/main/resources/seed/public-stats.csv
```

## 만드는 값 (모두 관측값)

| kind | 뜻 | 원본 |
| --- | --- | --- |
| `attractiveness` | 참고 상권 우리 업종 매출 합에서 그 업종 비중(매력도) | 추정매출 당월 매출 |
| `day_share` · `time_band_share` · `gender_share` · `age_share` | 그 업종 매출의 요일 · 시간대 6구간 · 성별 · 연령대(4구간으로 합침) 비중. 같은 묶음 안에서만 비율을 낸다 | 추정매출 |
| `clq` | A 업종 가게 반경 200m 안 이웃 중 B 비율의 평균 ÷ 서울 전체 B 비율. 1보다 크면 우연보다 많이 붙어 있다 | 상가정보 서울 전체 좌표 |

- 교차값(요일 × 시간대, 성별 × 연령대)은 저장하지 않는다. 추천 계산(E5)이 비중을 곱해 추정한다(P-1, 추정값임을 밝힘).
- 공방 · 소품샵은 추정매출에 대응 업종이 없어 매출 행을 만들지 않는다. 추천 계산에서 중립값으로 둔다(P-4). CLQ는 근사 분류(소품샵 = 기념품점 · 액세서리/잡화, 공방 = 상호에 '공방')로 계산한다.
- 업종 대응표 · 참고 상권 · 기준 분기 · 반경은 `config.json`. 음식점 = 중분류 I201 ~ I207 + 분식, 주점은 유흥 주점 제외(P-5).
