import PlaceholderPage from '@/pages/PlaceholderPage.jsx';
import NotFoundPage from '@/pages/public/NotFoundPage.jsx';

// 공용 영역: 역할과 상관없이 여는 화면. 담당 B(로그인 · 가입).
export const publicRoutes = [
  { path: '/login', element: <PlaceholderPage screenId="G-08" title="로그인" narrow /> }, // B4
  { path: '/signup', element: <PlaceholderPage screenId="G-09" title="회원가입" narrow /> }, // B4
  { path: '*', element: <NotFoundPage /> },
];
