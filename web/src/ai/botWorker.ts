import model from './model.json';

type Job = {id: string; observation: unknown; difficulty: string; seed: number};

// Authentication and game requests belong to the coordinator; the worker sees one bot's input.
const worker = self as unknown as {
    onmessage: ((event: MessageEvent<Job>) => void) | null;
    postMessage: (message: {id: string; command?: string; error?: string}) => void;
};
worker.onmessage = event => {
    const job = event.data;
    void import('aor-ai').then(module => {
        const command = module.chooseBotMove(JSON.stringify(job.observation), JSON.stringify(model), job.difficulty, job.seed);
        if (typeof command !== 'string' || !command.trim()) throw new Error('The automated player returned no action.');
        worker.postMessage({id: job.id, command});
    }).catch(() => worker.postMessage({id: job.id, error: 'BOT_POLICY_FAILED'}));
};
