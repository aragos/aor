import model from './model.json';
import purchaseModel from './purchaseModel.json';
import {createBotPolicyRunner} from './botPolicy';

type Job = {id: string; observation: unknown; purchase?: unknown; difficulty: 'EASY' | 'NORMAL' | 'HARD'; seed: number};
const choose = createBotPolicyRunner(() => import('aor-ai'), model, purchaseModel);

// Authentication and game requests belong to the coordinator; the worker sees one bot's input.
const worker = self as unknown as {
    onmessage: ((event: MessageEvent<Job>) => void) | null;
    postMessage: (message: {id: string; command?: string; error?: string}) => void;
};
worker.onmessage = event => {
    const job = event.data;
    void choose(job).then(command => {
        if (typeof command !== 'string' || !command.trim()) throw new Error('The automated player returned no action.');
        worker.postMessage({id: job.id, command});
    }).catch(() => worker.postMessage({id: job.id, error: 'BOT_POLICY_FAILED'}));
};
