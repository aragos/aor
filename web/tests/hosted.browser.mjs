import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {join} from 'node:path';
import {open} from './browser-support.mjs';

export async function checkHosted(page, web, artifacts) {
    const firebase = await readFile(new URL('./firebase-auth.stub.mjs', import.meta.url), 'utf8');
    const failures = [];
    const sessions = [];
    let account = null;
    let emailVerified = true;
    const intercept = async event => {
        const {method, params} = JSON.parse(event.data);
        if (method !== 'Fetch.requestPaused') return;
        try {
            const path = new URL(params.request.url).pathname;
            let body;
            if (path.endsWith('/firebase_auth.js')) body = firebase;
            else if (path === '/api/config') body = JSON.stringify({authMode: 'firebase', firebase: {
                apiKey: 'browser-test-key', projectId: 'browser-test', authDomain: 'browser-test.firebaseapp.com',
            }, pollIntervalMs: 1000});
            else {
                assert.equal(params.request.headers.Authorization, 'Bearer firebase-id-token');
                if (path === '/api/me') {
                    sessions.push(params.request.headers.Authorization);
                    body = JSON.stringify({id: 'google-player', emailVerified, account});
                } else if (path === '/api/games') body = JSON.stringify({games: [], nextCursor: null});
                else throw new Error(`Unexpected hosted request: ${path}`);
            }
            await page.send('Fetch.fulfillRequest', {requestId: params.requestId, responseCode: 200,
                responseHeaders: [{name: 'Content-Type', value: path.endsWith('.js') ? 'text/javascript' : 'application/json'}],
                body: Buffer.from(body).toString('base64')});
        } catch (failure) {
            failures.push(failure);
            await page.send('Fetch.failRequest', {requestId: params.requestId, errorReason: 'Failed'});
        }
    };
    page.ws.addEventListener('message', intercept);
    await page.send('Fetch.enable', {patterns: [{urlPattern: '*firebase_auth.js*'}, {urlPattern: '*/api/*'}]});
    try {
        async function check(description, action) {
            account = null;
            emailVerified = true;
            const previousDocument = await page.evaluate('performance.timeOrigin');
            await page.send('Page.navigate', {url: web});
            await page.wait(`performance.timeOrigin !== ${previousDocument} && window.firebaseTest && document.querySelector('.google-sign-in')`, 'Google sign-in in the new document');
            await action();
            assert.deepEqual(failures, []);
            console.log(`Passed: ${description}`);
        }
        await check('Google button artwork and desktop layout', async () => {
            await page.wait(`document.querySelector('.google-sign-in img').complete`, 'Google button artwork');
            await page.screenshot(join(artifacts, 'hosted-sign-in.png'), 1280, 900);
            assert.equal(await page.evaluate(`document.querySelector('.google-sign-in img').naturalWidth > 0`), true);
        });
        await check('Google popup disables concurrent sign-in actions', async () => {
            await page.click('Sign in with Google');
            await page.wait(`window.firebaseTest.popup !== null`, 'Google popup starts without email or password');
            assert.equal(await page.evaluate(`[...document.querySelectorAll('button')].every(button => button.disabled)`), true);
            assert.deepEqual(await page.evaluate(`window.firebaseTest.calls.find(call => call.method === 'signInWithPopup')`), {method: 'signInWithPopup', provider: 'google.com'});
        });
        for (const code of ['auth/popup-closed-by-user', 'auth/cancelled-popup-request']) {
            await check(`${code} allows a quiet retry`, async () => {
                const before = sessions.length;
                await page.click('Sign in with Google');
                await page.evaluate(`window.firebaseTest.finishPopup(${JSON.stringify(code)})`);
                await page.wait(`!document.querySelector('.google-sign-in').disabled`, 'Popup cancellation allows retry');
                assert.equal(await page.evaluate(`document.querySelector('[role=alert]') === null`), true);
                assert.equal(sessions.length, before, 'Cancellation does not request an authenticated session');
            });
        }

        for (const [code, message] of [
            ['auth/popup-blocked', 'Allow popups for this site'],
            ['auth/unauthorized-domain', 'Sign-in is not enabled for this website address'],
            ['auth/operation-not-allowed', 'This sign-in method has not been enabled'],
            ['auth/account-exists-with-different-credential', 'Use your existing method to sign in'],
        ]) {
            await check(`${code} explains how to continue`, async () => {
                await page.click('Sign in with Google');
                await page.evaluate(`window.firebaseTest.finishPopup(${JSON.stringify(code)})`);
                await page.wait(`document.querySelector('[role=alert]')?.textContent.includes(${JSON.stringify(message)})`, code);
                assert.equal(await page.evaluate(`document.querySelector('.google-sign-in').disabled`), false);
            });
        }

        await check('New Google users choose a player identity through the shared session flow', async () => {
            const before = sessions.length;
            await page.click('Create account');
            await page.click('Sign in with Google');
            await page.evaluate(`window.firebaseTest.finishPopup()`);
            await page.wait(`document.querySelector('input[autocomplete=username]')`, 'Player registration');
            assert.equal(sessions.length, before + 1, 'Firebase ID token opens the server session');
        });
        const registeredAccount = {id: 'google-player', handle: 'alice', displayName: 'Alice', admin: false, mayCreateGames: false};
        await check('Registered Google users see their games', async () => {
            account = registeredAccount;
            await page.click('Sign in with Google');
            await page.evaluate(`window.firebaseTest.finishPopup()`);
            await page.wait(`document.querySelector('.portal')`, 'Registered player dashboard');
            assert.equal(await page.evaluate(`document.querySelector('.account-bar').textContent.includes('@alice')`), true);
        });
        await check('Google users can sign out', async () => {
            await page.click('Sign in with Google');
            await page.evaluate(`window.firebaseTest.finishPopup()`);
            await page.wait(`document.querySelector('.account-bar')`, 'Signed-in user');

            await page.click('Sign out');

            await page.wait(`document.querySelector('.google-sign-in')`, 'Sign out returns to sign-in');
            assert.equal(await page.evaluate(`document.querySelector('.account-bar') === null`), true);
        });
        await check('Server email verification remains required for Google users', async () => {
            emailVerified = false;
            await page.click('Sign in with Google');
            await page.evaluate(`window.firebaseTest.finishPopup()`);
            await page.wait(`document.querySelector('h1')?.textContent === 'Verify your email'`, 'Server verification requirement');
            assert.equal(await page.evaluate(`document.querySelector('input[autocomplete=username]') === null`), true);
        });
        await check('Email and password sign-in still opens the dashboard', async () => {
            account = registeredAccount;
            await page.fill('input[type=email]', 'player@example.com');
            await page.fill('input[type=password]', 'fixture-password');
            await page.evaluate(`document.querySelector('form').requestSubmit()`);
            await page.wait(`document.querySelector('.portal')`, 'Email and password sign-in');
            assert.equal(await page.evaluate(`window.firebaseTest.calls.filter(call => call.method === 'signInWithEmailAndPassword').length`), 1);
        });
        await check('Sign-in fits a phone with a usable Google touch target', async () => {
            await page.screenshot(join(artifacts, 'hosted-sign-in-mobile.png'), 390, 844, true);
            assert.equal(await page.evaluate(`document.documentElement.scrollWidth <= innerWidth + 1`), true);
            assert.equal(await page.evaluate(`document.querySelector('.google-sign-in').getBoundingClientRect().height >= 44`), true);
        });
    } finally {
        await page.send('Fetch.disable');
        page.ws.removeEventListener('message', intercept);
        await page.send('Emulation.clearDeviceMetricsOverride');
    }
}

if (process.env.AOR_BROWSER_SUITE === 'hosted.browser.mjs') {
    const page = await open('about:blank');
    try {
        await checkHosted(page, process.env.MAP_WEB_URL, process.env.AOR_BROWSER_ARTIFACTS);
    } finally {
        page.ws.close();
        await fetch(`${process.env.MAP_CDP_URL}/json/close/${page.target.id}`);
    }
}
