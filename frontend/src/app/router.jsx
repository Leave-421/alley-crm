import { createBrowserRouter } from 'react-router';
import RootLayout from '@/layouts/RootLayout.jsx';
import { publicRoutes } from '@/routes/publicRoutes.jsx';
import { customerRoutes } from '@/routes/customerRoutes.jsx';
import { ownerRoutes } from '@/routes/ownerRoutes.jsx';
import { associationRoutes } from '@/routes/associationRoutes.jsx';

// 영역별 라우트 파일을 합치기만 한다. 경로는 이 파일이 아니라 각 영역 파일에 더한다
// (기획 저장소 00-backlog 「같이 쓰는 것 규칙」 — 프론트 라우트).
// RootLayout은 모든 화면에 시연 데이터 표시를 붙인다(NFR-10).
export const router = createBrowserRouter([
  {
    element: <RootLayout />,
    children: [...publicRoutes, ...customerRoutes, ...ownerRoutes, ...associationRoutes],
  },
]);
