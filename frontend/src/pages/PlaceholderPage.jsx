/** 화면 이슈가 실제 화면으로 바꾸기 전까지 경로만 잡아 두는 자리표시. */
export default function PlaceholderPage({ screenId, title }) {
  return (
    <main>
      <h1>{title}</h1>
      <p>
        {screenId} 화면 자리입니다. 해당 화면 이슈에서 만듭니다.
      </p>
    </main>
  );
}
