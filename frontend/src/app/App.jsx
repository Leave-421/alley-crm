import { RouterProvider } from 'react-router/dom';
import SessionProvider from '@/auth/SessionProvider.jsx';
import { router } from '@/app/router.jsx';

export default function App() {
  return (
    <SessionProvider>
      <RouterProvider router={router} />
    </SessionProvider>
  );
}
