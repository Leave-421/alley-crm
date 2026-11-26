import NotFoundPage from '@/pages/public/NotFoundPage.jsx';
import DemoToolsPage from '@/pages/demo/DemoToolsPage.jsx';
import LoginPage from '@/pages/auth/LoginPage.jsx';
import SignupPage from '@/pages/auth/SignupPage.jsx';
import StoreJoinPage from '@/pages/owner/join/StoreJoinPage.jsx';

// 공용 영역: 역할과 상관없이 여는 화면. 담당 B(로그인 · 가입 · 가게 가입 신청 · 시연 도구).
export const publicRoutes = [
  { path: '/login', element: <LoginPage /> }, // G-08 B4
  { path: '/signup', element: <SignupPage /> }, // G-09 B4
  { path: '/store-join', element: <StoreJoinPage /> }, // O-11 B5 (신청 · 승인 대기 · 거절 뒤 다시 신청)
  { path: '/demo', element: <DemoToolsPage /> }, // B20 시연 도구(역할 전환 · 서버 시각)
  { path: '*', element: <NotFoundPage /> },
];
