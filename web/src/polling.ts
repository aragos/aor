/** A recent game change or visit keeps hidden game tabs responsive during active play. */
const recentBackgroundPollIntervalMs = 5000;
const backgroundPollIntervalMs = 30000;
const recentActivityWindowMs = 120000;

/** Own one cancellable read and one timer, including across page visibility and resume events. */
export function startPolling({intervalMs, poll, onError, paused, activityAt}: {
    intervalMs: number;
    poll: (signal: AbortSignal) => Promise<void>;
    onError: (failure: unknown) => void;
    paused: () => boolean;
    /** Last accepted change or successful mutation, in milliseconds since the Unix epoch. */
    activityAt?: () => number;
}): () => void {
    let stopped = false;
    let timer: ReturnType<typeof setTimeout>;
    let request: AbortController | null = null;
    let refreshPending = false;
    let engagementAt = Date.now();

    function schedule() {
        clearTimeout(timer);
        const recent = Date.now() - Math.max(engagementAt, activityAt?.() ?? 0) < recentActivityWindowMs;
        const backgroundInterval = recent ? recentBackgroundPollIntervalMs : backgroundPollIntervalMs;
        timer = setTimeout(() => {void run();}, document.hidden ? backgroundInterval : intervalMs);
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
        if (!document.hidden) engagementAt = Date.now();
        clearTimeout(timer);
        if (request) {refreshPending = true; request.abort();}
        else void run();
    }
    function visibilityChanged() {
        engagementAt = Date.now();
        refresh();
    }
    document.addEventListener('visibilitychange', visibilityChanged);
    document.addEventListener('resume', refresh);
    window.addEventListener('online', refresh);
    window.addEventListener('pageshow', refresh);
    void run();
    return () => {
        stopped = true;
        clearTimeout(timer);
        request?.abort();
        document.removeEventListener('visibilitychange', visibilityChanged);
        document.removeEventListener('resume', refresh);
        window.removeEventListener('online', refresh);
        window.removeEventListener('pageshow', refresh);
    };
}
