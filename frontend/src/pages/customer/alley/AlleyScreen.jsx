import { useParams, useSearchParams } from 'react-router';
import AlleyScroll from '@/map/AlleyScroll.jsx';
import { ALLEYS } from '@/map/storeCatalog.js';
import NotFoundPage from '@/pages/public/NotFoundPage.jsx';

/**
 * G-01 골목(3D 스크롤) · 가게 라우트 진입점(A1, #4).
 * `:alley`는 CONTRACTS.md 「3」 store.alley 값(front_gate · center · back_street).
 * `?focus=storeId` 쿼리로 들어오면 그 가게를 기준 가게로 고른 상태로 연다 —
 * 오늘의 코스(G-14, A20)에서 들어올 때 쓴다(FR-CRS-13).
 */
export default function AlleyScreen() {
  const { alley } = useParams();
  const [searchParams] = useSearchParams();

  if (!ALLEYS.includes(alley)) return <NotFoundPage />;

  return <AlleyScroll alley={alley} initialSelectedId={searchParams.get('focus')} backTo="/alleys" />;
}
