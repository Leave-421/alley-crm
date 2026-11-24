import { Link } from 'react-router';

export default function NotFoundPage() {
  return (
    <main>
      <h1>없는 화면이에요</h1>
      <p>주소를 다시 확인해 주세요.</p>
      <Link to="/">처음으로 가기</Link>
    </main>
  );
}
