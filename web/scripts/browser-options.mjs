import {resolve} from 'node:path';

/** CLI suites override the legacy single-suite environment setting. Timeouts are per suite. */
export function browserOptions(args, env, testsDirectory) {
    const names = args.length ? args : [env.AOR_BROWSER_SUITE ?? 'map.browser.mjs'];
    const suites = names.map(name => {
        if (!/^[\w-]+\.browser\.mjs$/.test(name)) {
            throw new Error(`Expected a browser suite filename, received: ${name}`);
        }
        return {name, path: resolve(testsDirectory, name)};
    });
    const timeout = Number(env.AOR_BROWSER_TIMEOUT_MS ?? 300000);
    if (!Number.isSafeInteger(timeout) || timeout <= 0 || timeout > 2147483647) {
        throw new Error('AOR_BROWSER_TIMEOUT_MS must be an integer between 1 and 2147483647.');
    }
    return {suites, timeout};
}
