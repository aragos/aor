import type {BotJob, BotWork} from '../api.ts';

export type BotRunnerStatus = 'starting' | 'waiting' | 'thinking' | 'other' | 'paused' | 'stopped' | 'uncertain' | 'finished';

export interface BotRunnerDependencies {
    runner: (operation: 'claim' | 'renew', epoch: number, signal: AbortSignal) => Promise<BotWork>;
    work: (epoch: number, signal: AbortSignal) => Promise<BotWork>;
    submit: (work: BotWork, job: BotJob, command: string, operationId: string, signal: AbortSignal) => Promise<BotWork>;
    think: (job: BotJob, signal: AbortSignal) => Promise<string>;
    changed: () => Promise<void>;
    suspended: () => boolean;
    status: (value: BotRunnerStatus) => void;
    uuid: () => string;
    now?: () => number;
}

function failureCode(failure: unknown): string {
    return typeof failure === 'object' && failure !== null && 'code' in failure ? String(failure.code) : 'BOT_POLICY_FAILED';
}

/** One lease and serial decision loop serve every automated seat. Stop fences all local callbacks. */
export function startBotRunner(initialEpoch: number, dependencies: BotRunnerDependencies) {
    const controller = new AbortController();
    const {signal} = controller;
    const now = dependencies.now ?? Date.now;
    let wakeUp: (() => void) | undefined;
    let epoch = initialEpoch;
    let acquired = false;
    let pending: BotWork | undefined;
    let leaseUntil = 0;
    const status = (value: BotRunnerStatus) => {if (!signal.aborted) dependencies.status(value);};
    function wait(milliseconds: number) {
        return new Promise<void>(resolve => {
            const finish = () => {
                clearTimeout(timer);
                signal.removeEventListener('abort', finish);
                if (wakeUp === finish) wakeUp = undefined;
                resolve();
            };
            const timer = setTimeout(finish, milliseconds);
            wakeUp = finish;
            signal.addEventListener('abort', finish, {once: true});
            if (signal.aborted) finish();
        });
    }
    async function publishChange() {
        if (signal.aborted) return;
        try {await dependencies.changed();}
        catch { /* The table's regular authorized poll reconciles a missed refresh. */ }
    }
    async function run() {
        status('starting');
        while (!signal.aborted) {
            try {
                if (dependencies.suspended()) {await wait(150); continue;}
                const work = pending ?? (leaseUntil - now() <= 10000
                    ? await dependencies.runner(acquired ? 'renew' : 'claim', epoch, signal)
                    : await dependencies.work(epoch, signal));
                pending = undefined;
                if (signal.aborted) return;
                epoch = work.epoch;
                leaseUntil = work.leaseUntil;
                acquired = true;
                if (work.paused) {
                    status('paused');
                    await publishChange();
                    return;
                }
                const job = work.observations[0];
                if (!job) {status('waiting'); await wait(2000); continue;}
                status('thinking');
                const command = await dependencies.think(job, signal);
                if (signal.aborted) return;
                // Human mutations can change decisions without advancing the public revision.
                if (dependencies.suspended()) continue;
                const operationId = dependencies.uuid();
                try {
                    pending = await dependencies.submit(work, job, command, operationId, signal);
                } catch (failure) {
                    if (signal.aborted) return;
                    if (failureCode(failure) !== 'OUTCOME_UNKNOWN') throw failure;
                    const reconciled = await dependencies.work(epoch, signal);
                    if (signal.aborted) return;
                    await publishChange();
                    if (!reconciled.operationReceipts?.includes(operationId)) {
                        status('uncertain');
                        return;
                    }
                    pending = reconciled;
                }
                if (signal.aborted) return;
                await publishChange();
            } catch (failure) {
                if (signal.aborted) return;
                const code = failureCode(failure);
                if (['STALE_REVISION', 'STALE_SUBMISSION', 'BOT_DECISION_CHANGED'].includes(code)) {
                    pending = undefined;
                    continue;
                }
                if (['BOT_RUNNER_BUSY', 'BOT_LEASE_HELD'].includes(code)) {
                    acquired = false;
                    leaseUntil = 0;
                    status('other');
                    await wait(10000);
                    continue;
                }
                if (code === 'BOT_RUNNER_EXPIRED') {
                    acquired = false;
                    leaseUntil = 0;
                    pending = undefined;
                    continue;
                }
                if (code === 'BOT_PAUSED') {
                    status('paused');
                    await publishChange();
                    return;
                }
                status(code === 'OUTCOME_UNKNOWN' ? 'uncertain' : 'stopped');
                await publishChange();
                return;
            }
        }
    }
    const done = run();
    return {
        done,
        wake: () => wakeUp?.(),
        stop: () => controller.abort(),
    };
}
