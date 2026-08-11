import { fireEvent, render, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AuthProviderWithRouter, useAuth } from '../AuthContext';

const mocks = vi.hoisted(() => ({
  authLogout: vi.fn(() => new Promise(() => {})),
  disconnect: vi.fn(),
  clearStoredUser: vi.fn(),
  replace: vi.fn(() => Promise.resolve()),
}));

vi.mock('../../services/authService', () => ({ default: { logout: mocks.authLogout } }));
vi.mock('../../services/socket', () => ({ default: { disconnect: mocks.disconnect } }));
vi.mock('../../lib/auth/authStorage', () => ({
  clearStoredUser: mocks.clearStoredUser,
  getLastTokenVerification: vi.fn(),
  loadStoredUser: () => ({ token: 'token-1', sessionId: 'session-1' }),
  saveLastTokenVerification: vi.fn(),
  saveStoredUser: vi.fn((user) => user),
}));

const LogoutButton = () => {
  const { logout } = useAuth();
  return <button onClick={logout}>logout</button>;
};

describe('AuthContext logout', () => {
  it('moves to the login page without waiting for a delayed server logout', async () => {
    render(
      <AuthProviderWithRouter router={{ replace: mocks.replace }}>
        <LogoutButton />
      </AuthProviderWithRouter>
    );

    fireEvent.click(document.querySelector('button'));

    await waitFor(() => {
      expect(mocks.authLogout).toHaveBeenCalledWith('token-1', 'session-1');
      expect(mocks.disconnect).toHaveBeenCalledOnce();
      expect(mocks.clearStoredUser).toHaveBeenCalledOnce();
      expect(mocks.replace).toHaveBeenCalledWith('/');
    });
  });
});
