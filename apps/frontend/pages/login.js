import { useEffect } from 'react';
import LoginPage from './index';

export default function LoginAliasPage() {
  useEffect(() => {
    const canonicalUrl = `/${window.location.search}${window.location.hash}`;
    window.history.replaceState(window.history.state, '', canonicalUrl);
  }, []);

  return <LoginPage />;
}
