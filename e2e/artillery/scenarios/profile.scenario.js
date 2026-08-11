const {
    goToProfileAction,
} = require('../../actions/profile.actions');
const { expect } = require('@playwright/test');
const fs = require('fs');
const path = require('path');

const BASE_URL = process.env.BASE_URL || 'http://localhost:3000';
const PROFILE_IMAGE_PATH = path.resolve(__dirname, '../../fixtures/images/profile.jpg');
const ARTIFACT_DIR = path.resolve(__dirname, '../artifacts');

// Action 간 timeout 설정 (환경변수로 조절 가능)
const ACTION_TIMEOUT_SHORT = parseInt(process.env.ACTION_TIMEOUT_SHORT || '500', 10);

function logDiagnostic(event, details = {}) {
    console.log(`[artillery-profile] ${JSON.stringify({
        event,
        timestamp: new Date().toISOString(),
        ...details,
    })}`);
}

function emitCounter(vuContext, name) {
    vuContext?.events?.emit('counter', `diagnostic.profile.${name}`, 1);
}

function errorDetails(error) {
    return {
        name: error?.name,
        message: error?.message,
    };
}

async function readJsonSafely(response) {
    try {
        return await response.json();
    } catch (error) {
        return { parseError: error.message };
    }
}

/**
 * Artillery 전체 프로필 업데이트 시나리오
 * 이름 변경 + 이미지 업로드
 */
async function fullProfileUpdateScenario(page, vuContext) {
    let stage = 'profile-page';

    const onResponse = response => {
        if (response.status() >= 400) {
            logDiagnostic('http-error', {
                stage,
                method: response.request().method(),
                status: response.status(),
                url: response.url(),
            });
            emitCounter(vuContext, `http_status_${response.status()}`);
        }
    };
    const onRequestFailed = request => {
        logDiagnostic('request-failed', {
            stage,
            method: request.method(),
            url: request.url(),
            failure: request.failure()?.errorText,
        });
        emitCounter(vuContext, 'request_failed');
    };
    const onPageError = error => {
        logDiagnostic('page-error', { stage, ...errorDetails(error) });
        emitCounter(vuContext, 'page_error');
    };

    page.on('response', onResponse);
    page.on('requestfailed', onRequestFailed);
    page.on('pageerror', onPageError);

    try {
        // 1. 프로필 페이지 이동
        await goToProfileAction(page);
        await page.waitForTimeout(ACTION_TIMEOUT_SHORT);

        // 2. 이미지 업로드
        stage = 'profile-image-upload';
        const uploadStartedAt = Date.now();
        const uploadResponsePromise = page.waitForResponse(
            response =>
                response.url().includes('/api/users/profile-image') &&
                response.request().method() === 'POST',
            { timeout: 20000 }
        );

        await page.getByTestId('profile-image-file-input').setInputFiles(PROFILE_IMAGE_PATH);
        const uploadResponse = await uploadResponsePromise;
        const uploadBody = await readJsonSafely(uploadResponse);

        logDiagnostic('profile-image-upload-response', {
            status: uploadResponse.status(),
            durationMs: Date.now() - uploadStartedAt,
            url: uploadResponse.url(),
            imageUrl: uploadBody?.imageUrl,
            response: uploadResponse.ok() ? undefined : uploadBody,
        });
        emitCounter(vuContext, `upload_status_${uploadResponse.status()}`);
        expect(uploadResponse.ok(), 'profile image upload response').toBeTruthy();

        // 로컬 스토리지를 여러 서버가 각자 사용할 때 발생하는 간헐적 404를
        // 업로드 성공 여부와 분리해 관찰한다. 이 진단 GET은 기존 판정을 바꾸지 않는다.
        if (uploadBody?.imageUrl) {
            stage = 'profile-image-read';
            const imageUrl = new URL(uploadBody.imageUrl, uploadResponse.url()).toString();
            const imageReadStartedAt = Date.now();
            const imageResponse = await page.request.get(imageUrl, { timeout: 15000 });

            logDiagnostic('profile-image-read-response', {
                status: imageResponse.status(),
                durationMs: Date.now() - imageReadStartedAt,
                url: imageUrl,
            });
            emitCounter(vuContext, `image_read_status_${imageResponse.status()}`);
        }

        // 2-1. 이미지 업로드 검증
        stage = 'profile-image-success-toast';
        await expect(page.getByTestId('toast-success')).toBeVisible();
        emitCounter(vuContext, 'image_success_toast_visible');
        await page.waitForTimeout(ACTION_TIMEOUT_SHORT);

        // 3. 이름 변경
        stage = 'profile-name-fill';
        const newName = `풀업데이트_${Math.random().toString(36).substring(2, 8)}`;
        await page.getByTestId('profile-name-input').fill(newName);
        await page.waitForTimeout(ACTION_TIMEOUT_SHORT);

        // 4. 저장
        stage = 'profile-save';
        const saveStartedAt = Date.now();
        const saveResponsePromise = page.waitForResponse(
            response =>
                response.url().includes('/api/users/profile') &&
                response.request().method() === 'PUT',
            { timeout: 15000 }
        );

        await page.getByTestId('profile-save-button').click();
        const saveResponse = await saveResponsePromise;

        logDiagnostic('profile-save-response', {
            status: saveResponse.status(),
            durationMs: Date.now() - saveStartedAt,
            url: saveResponse.url(),
        });
        emitCounter(vuContext, `save_status_${saveResponse.status()}`);
        expect(saveResponse.ok(), 'profile save response').toBeTruthy();

        // 5. 성공 확인
        stage = 'profile-save-success-message';
        await expect(page.getByTestId('profile-success-message')).toBeVisible();
        emitCounter(vuContext, 'save_success_message_visible');

        stage = 'profile-name-value';
        await expect(page.getByTestId('profile-name-input')).toHaveValue(newName);

        stage = 'profile-image-avatar';
        await expect(page.getByTestId('profile-image-avatar')).toBeVisible();
        emitCounter(vuContext, 'completed');
    } catch (error) {
        emitCounter(vuContext, `failed_at_${stage.replace(/-/g, '_')}`);
        logDiagnostic('scenario-failed', { stage, ...errorDetails(error) });

        try {
            await fs.promises.mkdir(ARTIFACT_DIR, { recursive: true });
            const screenshotPath = path.join(
                ARTIFACT_DIR,
                `profile-${stage}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}.png`
            );
            await page.screenshot({ path: screenshotPath, fullPage: true });
            logDiagnostic('failure-screenshot', { stage, path: screenshotPath });
        } catch (screenshotError) {
            logDiagnostic('failure-screenshot-error', {
                stage,
                ...errorDetails(screenshotError),
            });
        }

        console.error('Full profile update scenario failed:', error.message);
        throw error;
    } finally {
        page.off('response', onResponse);
        page.off('requestfailed', onRequestFailed);
        page.off('pageerror', onPageError);
    }
}

module.exports = {
    fullProfileUpdateScenario,
};
