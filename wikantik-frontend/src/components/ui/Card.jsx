// Card.jsx
// Elevated surface container with optional interactivity.
export default function Card({ as: Component = 'div', className = '', children, ...rest }) {
  return (
    <Component className={`surface ${className}`.trim()} {...rest}>
      {children}
    </Component>
  );
}
