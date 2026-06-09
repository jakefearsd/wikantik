// Select.jsx
// Thin styled wrapper over a native <select>. options may be strings or {value,label}.
export default function Select({
  value,
  options = [],
  onChange,
  disabled = false,
  id,
  ariaLabel,
  placeholder,
  'data-testid': dataTestId,
}) {
  return (
    <select
      className="ui-select"
      value={value ?? ''}
      disabled={disabled}
      id={id}
      aria-label={ariaLabel}
      data-testid={dataTestId}
      onChange={(e) => onChange?.(e.target.value)}
    >
      {placeholder != null && <option value="">{placeholder}</option>}
      {options.map((o) => {
        const v = typeof o === 'string' ? o : o.value;
        const label = typeof o === 'string' ? o : (o.label ?? o.value);
        return (
          <option key={v} value={v}>
            {label}
          </option>
        );
      })}
    </select>
  );
}
