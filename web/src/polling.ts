/** Hidden tabs check for a new decision without using the foreground polling rate. */
const backgroundPollIntervalMs = 30000;

/** Own one cancellable read and one timer, including across page visibility and resume events. */
export function startPolling({intervalMs, poll, onError, paused}: {
    intervalMs: number;
    poll: (signal: AbortSignal) => Promise<void>;
    onError: (failure: unknown) => void;
    paused: () => boolean;
}): () => void {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    let request: AbortController | null = null;
    let refreshPending = false;

    function schedule() {
        clearTimeout(timer);
        timer = setTimeout(() => {void run();}, document.hidden ? backgroundPollIntervalMs : intervalMs);
    }
    async function run() {
        if (stopped || request) return;
        clearTimeout(timer);
        if (paused()) {schedule(); return;}
        const current = new AbortController();
        request = current;
        try {await poll(current.signal);}
        catch (failure) {if (!stopped && !current.signal.aborted) onError(failure);}
        finally {
            request = null;
            if (!stopped) {
                if (refreshPending) {refreshPending = false; void run();}
                else schedule();
            }
        }
    }
    function refresh() {
        if (stopped) return;
        clearTimeout(timer);
        if (request) {refreshPending = true; request.abort();}
        else void run();
    }
    document.addEventListener('visibilitychange', refresh);
    document.addEventListener('resume', refresh);
    window.addEventListener('online', refresh);
    window.addEventListener('pageshow', refresh);
    void run();
    return () => {
        stopped = true;
        clearTimeout(timer);
        request?.abort();
        document.removeEventListener('visibilitychange', refresh);
        document.removeEventListener('resume', refresh);
        window.removeEventListener('online', refresh);
        window.removeEventListener('pageshow', refresh);
    };
}
