import type {BotJob} from '../api';

/** The single worker is terminated on cancellation, so obsolete synchronous searches cannot linger. */
export function createBotWorkerClient() {
    let worker: Worker | undefined;
    let rejectPending: ((reason: Error) => void) | undefined;
    function stop() {
        worker?.terminate();
        worker = undefined;
        rejectPending?.(new Error('Automated decision canceled.'));
        rejectPending = undefined;
    }
    function think(job: BotJob, signal: AbortSignal): Promise<string> {
        if (signal.aborted) return Promise.reject(new Error('Automated decision canceled.'));
        if (rejectPending) return Promise.reject(new Error('Only one automated decision can run at a time.'));
        worker ??= new Worker(new URL('./botWorker.ts', import.meta.url), {type: 'module'});
        const current = worker;
        return new Promise<string>((resolve, reject) => {
            const id = crypto.randomUUID();
            const clean = () => {
                clearTimeout(timer);
                signal.removeEventListener('abort', cancel);
                current.removeEventListener('message', message);
                current.removeEventListener('error', failed);
                rejectPending = undefined;
            };
            const fail = () => {clean(); stop(); reject(new Error('Automated policy failed.'));};
            const cancel = () => {clean(); stop(); reject(new Error('Automated decision canceled.'));};
            const failed = () => fail();
            const message = (event: MessageEvent<{id: string; command?: string; error?: string}>) => {
                if (event.data.id !== id) return;
                if (event.data.error || !event.data.command) {fail(); return;}
                clean(); resolve(event.data.command);
            };
            const timer = setTimeout(fail, 10000);
            rejectPending = reason => {clean(); reject(reason);};
            current.addEventListener('message', message);
            current.addEventListener('error', failed);
            signal.addEventListener('abort', cancel, {once: true});
            current.postMessage({id, observation: job.observation, difficulty: job.difficulty, seed: job.seed});
        });
    }
    return {think, stop};
}
