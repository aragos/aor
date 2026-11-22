import {hardCheckpoint} from './hardCheckpoints';
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
const modelByteLimit = 150_000;
const selectors = new Map<string, Promise<HardBotSelector>>();
let legacyModel: Promise<string> | undefined;

async function loadCheckpoint(version: string, playerCount: number): Promise<HardBotSelector> {
    const asset = hardCheckpoint(version, playerCount);
    const response = await fetch(asset.url, {credentials: 'omit'});
    if (!response.ok) throw new Error('The automated opponent checkpoint is unavailable.');
    const bytes = await response.arrayBuffer();
    if (bytes.byteLength === 0 || bytes.byteLength > modelByteLimit) {
        throw new Error('The automated opponent checkpoint exceeds its size limit.');
    }
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    const hash = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
    if (hash !== asset.sha256) throw new Error('The automated opponent checkpoint failed verification.');
    const {HardBotSelector} = await import('aor-ai');
    return new HardBotSelector(new TextDecoder().decode(bytes), playerCount, asset.market);
}

async function choose(job: Job): Promise<string> {
    const observation = JSON.stringify(job.observation);
    if (!job.policyVersion || job.policyVersion === legacyVersion) {
        legacyModel ??= import('./model.json').then(module => JSON.stringify(module.default));
        const [module, model] = await Promise.all([import('aor-ai'), legacyModel]);
        return module.chooseBotMove(observation, model, job.difficulty, job.seed);
    }
    const count = job.observation.configuration?.playerCount ?? 0;
    const key = `${job.policyVersion}/${count}`;
    let selector = selectors.get(key);
    if (!selector) {
        selector = loadCheckpoint(job.policyVersion, count);
        selectors.set(key, selector);
        selector.catch(() => selectors.delete(key));
    }
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
