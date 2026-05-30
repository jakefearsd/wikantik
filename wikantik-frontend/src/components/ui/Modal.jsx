import { useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { useFocusTrap } from '../../hooks/useFocusTrap';
import { useScrollLock } from '../../hooks/useScrollLock';

/**
 * Return (or lazily create) the #modal-root node.
 * index.html already contains <div id="modal-root"></div>; this fallback
 * handles test environments or server-side contexts where the node may be absent.
 */
function getModalRoot() {
  let root = document.getElementById('modal-root');
  if (!root) {
    root = document.createElement('div');
    root.id = 'modal-root';
    document.body.appendChild(root);
  }
  return root;
}

/**
 * Portal-based modal shell.
 *
 * Props:
 *   isOpen     {boolean}        Whether the modal is visible.
 *   onClose    {() => void}     Called on Esc key or backdrop click.
 *   children   {React.ReactNode}
 *   labelledBy {string}         id of the element that labels this dialog.
 *   className  {string}         Optional extra class on the content element.
 */
export default function Modal({ isOpen, onClose, children, labelledBy, className }) {
  const contentRef = useRef(null);

  // Activate focus trap and scroll lock while the modal is open.
  useFocusTrap(contentRef, isOpen);
  useScrollLock(isOpen);

  // Esc key closes the modal.
  useEffect(() => {
    if (!isOpen) return;
    function handleKeyDown(e) {
      if (e.key === 'Escape') onClose();
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  if (!isOpen) return null;

  const contentClasses = ['modal-content', className].filter(Boolean).join(' ');

  return createPortal(
    <div
      className="modal-overlay modal-overlay-centered"
      onClick={onClose}
    >
      <div
        ref={contentRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={labelledBy}
        className={contentClasses}
        onClick={(e) => e.stopPropagation()}
      >
        {children}
      </div>
    </div>,
    getModalRoot(),
  );
}
