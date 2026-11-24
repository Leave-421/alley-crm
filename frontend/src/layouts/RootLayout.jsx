import { Outlet } from 'react-router';
import DemoDataBanner from '@/components/DemoDataBanner/DemoDataBanner.jsx';

/** 모든 경로의 바깥 틀. 로그인 · 403 · 404를 포함해 모든 화면에 시연 데이터 표시를 붙인다(NFR-10). */
export default function RootLayout() {
  return (
    <>
      <DemoDataBanner />
      <Outlet />
    </>
  );
}
