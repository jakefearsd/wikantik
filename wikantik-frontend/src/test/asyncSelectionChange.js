// Side-effect import for suites that run the REAL CodeMirror editor under happy-dom (see below). Not in
// setupTests.js on purpose: other suites rely on happy-dom's synchronous selectionchange.
// Browsers QUEUE `selectionchange` (a task) after a Selection mutation, and fire it only when the selection really
// moved. happy-dom dispatches it synchronously, and on every collapse()/addRange() even to an identical position.
// CodeMirror 6.43.14+ runs `view.update([])` from that event, so the synchronous dispatch re-enters an update
// already in progress and throws. Defer it like a browser: one pending task at a time, dropped if the selection
// is where the last delivered event left it.
{
  const nativeDispatch = document.dispatchEvent.bind( document );
  let pending = false;
  let delivered = '';
  const signature = () => {
    const sel = window.getSelection();
    return sel ? [ sel.anchorNode, sel.anchorOffset, sel.focusNode, sel.focusOffset ].map( String ).join( '|' ) +
      ( sel.anchorNode ? '#' + ( sel.anchorNode.nodeName ) : '' ) : '';
  };
  document.dispatchEvent = function dispatchEvent( event ) {
    if ( event && event.type === 'selectionchange' ) {
      if ( !pending ) {
        pending = true;
        setTimeout( () => {
          pending = false;
          const now = signature();
          if ( now !== delivered ) {
            delivered = now;
            nativeDispatch( event );
          }
        }, 0 );
      }
      return true;
    }
    return nativeDispatch( event );
  };
}

