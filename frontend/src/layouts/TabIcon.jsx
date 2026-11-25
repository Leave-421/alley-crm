// 하단 탭 아이콘. 모양은 확정 목업의 하단 탭 SVG를 그대로 옮겼다(24×24, 선 2px).
const PATHS = {
  home: (
    <>
      <path d="M3 11l9-8 9 8" />
      <path d="M5 10v10h14V10" />
    </>
  ),
  calendar: (
    <>
      <rect x="3" y="5" width="18" height="16" rx="3" />
      <path d="M3 10h18M8 3v4M16 3v4" />
    </>
  ),
  person: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4 21a8 8 0 0116 0" />
    </>
  ),
  people: (
    <>
      <circle cx="9" cy="8" r="3.5" />
      <path d="M2.5 20a6.5 6.5 0 0113 0" />
      <path d="M16 4.6a3.5 3.5 0 010 6.8M18 14a6.5 6.5 0 013.5 6" />
    </>
  ),
  flow: (
    <>
      <path d="M7 4L3 8l4 4" />
      <path d="M3 8h14a4 4 0 014 4" />
      <path d="M17 20l4-4-4-4" />
      <path d="M21 16H7a4 4 0 01-4-4" />
    </>
  ),
  money: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M9 9.5c.5-1 1.5-1.5 3-1.5 2 0 3 1 3 2s-1 1.8-3 2-3 1-3 2 1 2 3 2c1.5 0 2.5-.5 3-1.5M12 6v2M12 16v2" />
    </>
  ),
  gift: (
    <>
      <rect x="3" y="9" width="18" height="12" rx="2" />
      <path d="M12 9v12M3 13h18M12 9c-2 0-4-1-4-3s3-2 4 1c1-3 4-3 4-1s-2 3-4 3z" />
    </>
  ),
  chart: <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />,
  store: (
    <>
      <path d="M4 9l1.5-5h13L20 9" />
      <path d="M4 9a2.7 2.7 0 005.3 0 2.7 2.7 0 005.4 0A2.7 2.7 0 0020 9" />
      <path d="M5 12v8h14v-8M10 20v-5h4v5" />
    </>
  ),
  bell: (
    <>
      <path d="M6 8a6 6 0 0112 0c0 7 3 9 3 9H3s3-2 3-9" />
      <path d="M10 21a2 2 0 004 0" />
    </>
  ),
};

export default function TabIcon({ name, size = 24, className }) {
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {PATHS[name] ?? null}
    </svg>
  );
}
