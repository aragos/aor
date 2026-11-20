import type {HardBotSelector} from 'aor-ai';

type Job = {
    id: string;
    observation: {configuration?: {playerCount: number}};
    difficulty: string;
    seed: number;
    policyVersion?: string;
    initiative?: unknown;
    finish?: unknown;
};

const legacyVersion = 'aor-policy-1';
const checkpoints = {
    'aor-hard-2026-11-20-1': {
        3: ['three-player-continuation-model.json', '7446542a3e85f3db8b31d0fda632ae0d4f1ff809188b68cfda5899841ff57624'],
        4: ['continuation-model.json', '956b39695f744ce835a72af6452e33d061b74479a8b13938e3b968f18ce4556e'],
        5: ['continuation-model.json', '956b39695f744ce835a72af6452e33d061b74479a8b13938e3b968f18ce4556e'],
        6: ['broad-model.json', 'a479db4b3c7f580ce9979049fe407cbde4db22bf75b76d3cc54ac6f2039ce500'],
    },
} as const;
const modelByteLimit = 150_000;
let selector: Promise<HardBotSelector> | undefined;
let legacyModel: Promise<string> | undefined;

async function loadCheckpoint(version: string, playerCount: number): Promise<HardBotSelector> {
    if (!Object.prototype.hasOwnProperty.call(checkpoints, version) || playerCount < 3 || playerCount > 6) {
        throw new Error('This game requires an unavailable automated opponent checkpoint.');
    }
    const [file, expectedHash] = checkpoints[version as keyof typeof checkpoints][playerCount as 3 | 4 | 5 | 6];
    const response = await fetch(`/assets/automata/${version}/${file}`, {credentials: 'omit'});
    if (!response.ok) throw new Error('The automated opponent checkpoint is unavailable.');
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength === 0 || bytes.byteLength > modelByteLimit) {
        throw new Error('The automated opponent checkpoint exceeds its size limit.');
    }
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    const hash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
    if (hash !== expectedHash) throw new Error('The automated opponent checkpoint failed verification.');
    const {HardBotSelector} = await import('aor-ai');
    return new HardBotSelector(new TextDecoder().decode(bytes), playerCount);
}

async function choose(job: Job): Promise<string> {
    const observation = JSON.stringify(job.observation);
    if (!job.policyVersion || job.policyVersion === legacyVersion) {
        legacyModel ??= import('./model.json').then(module => JSON.stringify(module.default));
        const [module, model] = await Promise.all([import('aor-ai'), legacyModel]);
        return module.chooseBotMove(observation, model, job.difficulty, job.seed);
    }
    selector ??= loadCheckpoint(job.policyVersion, job.observation.configuration?.playerCount ?? 0);
    const policy = await selector;
    return policy.choose(observation,
        job.initiative == null ? null : JSON.stringify(job.initiative),
        job.finish == null ? null : JSON.stringify(job.finish),
        job.seed);
}

// Authentication and game writes belong to the coordinator; the worker sees one bot's input.
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
