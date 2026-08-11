const { expect } = require('@playwright/test');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';

/**
 * 로그인 액션
 * @param {import('@playwright/test').Page} page
 * @param {Object} credentials - { email: string, password: string }
 * @param waitForRedirect
 */
async function loginAction(page, credentials, waitForRedirect = true) {
  const loginEmailInput = page.getByTestId('login-email-input');
  if (!page.url().startsWith(`${BASE_URL}/login`)) {
    await page.goto(`${BASE_URL}/login`);
  }
  await expect(loginEmailInput).toBeVisible({ timeout: 10000 });
  await loginEmailInput.fill(credentials.email);
  await page.getByTestId('login-password-input').fill(credentials.password);
  await page.getByTestId('login-submit-button').click();
  await page.waitForTimeout(1000); // 잠시 대기
  if (waitForRedirect) {
    await page.waitForURL(`${BASE_URL}/chat`);
  }
}

/**
 * 회원가입 액션
 * @param {import('@playwright/test').Page} page
 * @param {Object} userData - { email: string, password: string, passwordConfirm: string, name: string }
 */
async function registerAction(page, userData) {
  await page.goto(`${BASE_URL}/register`);
  await page.getByTestId('register-email-input').fill(userData.email);
  await page.getByTestId('register-password-input').fill(userData.password);
  await page.getByTestId('register-password-confirm-input').fill(userData.passwordConfirm);
  await page.getByTestId('register-name-input').fill(userData.name);
  await page.getByTestId('register-submit-button').click();
  // 중요: 성공 라우팅뿐 아니라 로그인 폼 렌더링까지 기다려 다음 로그인 동작과 경합하지 않는다.
  const result = await Promise.race([
    page.waitForURL(`${BASE_URL}/login`, { timeout: 10000 }).then(() => 'login'),
    page.getByTestId('register-error-message')
      .waitFor({ state: 'visible', timeout: 10000 })
      .then(() => 'error'),
  ]);
  if (result === 'error') {
    throw new Error('회원가입에 실패했습니다.');
  }
  await expect(page.getByTestId('login-email-input')).toBeVisible({ timeout: 10000 });
}

/**
 * 로그아웃 액션
 * @param {import('@playwright/test').Page} page
 */
async function logoutAction(page) {
  await page.getByTestId('logout-link').click();
  // 고정 시간 대신 로그아웃 라우팅이 실제로 끝난 상태를 기다린다.
  await expect(page.getByTestId('login-email-input')).toBeVisible({ timeout: 10000 });
}

module.exports = {
  loginAction,
  registerAction,
  logoutAction,
};
