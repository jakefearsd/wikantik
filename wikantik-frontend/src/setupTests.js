import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

afterEach(cleanup);

// happy-dom does not implement the modal-dialog methods (alert/confirm/prompt),
// and vitest 4's happy-dom environment no longer auto-registers stubs the way
// vitest 1.x did. Define no-op stubs so component code can invoke them and tests
// can vi.spyOn(window, ...) / mockReturnValue them. Guarded so a future real
// happy-dom implementation isn't clobbered.
if ( typeof window.alert !== 'function' ) window.alert = () => {};
if ( typeof window.confirm !== 'function' ) window.confirm = () => false;
if ( typeof window.prompt !== 'function' ) window.prompt = () => null;
