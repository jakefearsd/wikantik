// Icon.jsx
// Inline SVG icon component with named paths.

const iconPaths = {
  edit: 'M3 17.25V21h3.75L17.81 9.94M21 7a2.828 2.828 0 1 1-4 4L7.79 19.25',
  trash: 'M19 6h-3.5V4a2 2 0 0 0-2-2h-3a2 2 0 0 0-2 2v2H5m1 0v14a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V6M9 9v6M15 9v6',
  comment: 'M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z',
  search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16M21 21l-4.35-4.35',
  sun: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0zM12 1v6M12 17v6M4.22 4.22l4.24 4.24M15.54 15.54l4.24 4.24M1 12h6M17 12h6M4.22 19.78l4.24-4.24M15.54 8.46l4.24-4.24',
  moon: 'M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z',
  copy: 'M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M9 11l-4.95-4.95A5 5 0 0 0 1 9.5a5 5 0 0 0 8.54 3.54l3-3a5 5 0 0 0-7.07-7.07l1.72 1.71',
  link: 'M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71M9 11l-4.95-4.95A5 5 0 0 0 1 9.5a5 5 0 0 0 8.54 3.54l3-3a5 5 0 0 0-7.07-7.07l1.72 1.71',
  chevron: 'M15 18l-6-6 6-6',
  close: 'M18 6L6 18M6 6l12 12',
  check: 'M20 6L9 17l-5-5',
  warning: 'M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3.05h16.94a2 2 0 0 0 1.71-3.05L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01',
  more: 'M12 5a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM12 13a2 2 0 1 0 0-4 2 2 0 0 0 0 4zM12 21a2 2 0 1 0 0-4 2 2 0 0 0 0 4z',
};

export default function Icon({ name, size = 16, title, className = '' }) {
  const pathData = iconPaths[name];

  if (!pathData) {
    if (typeof console !== 'undefined' && console.warn) {
      console.warn(`Icon: unknown icon name "${name}"`);
    }
    return null;
  }

  const ariaProps = title ? { role: 'img', 'aria-label': title } : { 'aria-hidden': 'true' };

  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      width={size}
      height={size}
      className={className}
      {...ariaProps}
    >
      <path d={pathData} />
    </svg>
  );
}
