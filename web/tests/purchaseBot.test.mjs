import assert from 'node:assert/strict';
import {test} from 'node:test';
import * as ai from '../../ai/build/dist/js/productionLibrary/aor-ai.mjs';
import {createBotPolicyRunner} from '../src/ai/botPolicy.ts';

const configuration = {playerCount: 4, map: 'STANDARD', initialCard: 'NO_CRUSADES', deckModification: true, immediateMasterArtDiscard: true};
const versions = {rulesVersion: 'ah1996-three-epoch-4', dataVersion: 'ah1996-catalog-16', configuration};
const baseline = {schemaVersion: 1, weights: {'test.baseline': 1}};
function fixture() {
    const zeros = () => Array(64).fill(0);
    const purchase = {encoderVersion: 'aor-purchase-observation-1', stateFeatures: {root: 1}, actions: [
        {command: 'buy-advance A', features: {choice: 1}}, {command: 'pass', features: {}},
    ]};
    const actor = {schemaVersion: 1, actorVersion: 'aor-purchase-policy-1', ...versions,
        encoderVersion: purchase.encoderVersion, baseline, model: {
            schemaVersion: 1, encoderVersion: purchase.encoderVersion, hiddenSize: 64, baselineProbability: .97,
            stateFeatureNames: ['root'], actionFeatureNames: ['choice'], stateWeights: zeros(), stateBias: zeros(),
            actionWeights: zeros(), actionBias: zeros(), actorWeights: zeros(), valueWeights: zeros(), valueBias: 0,
        }};
    const observation = {schemaVersion: 1, ...versions, playerId: 'p1', decision: 'purchase-advances',
        context: {}, availableCash: 10, availableTokens: 0, candidates: [
            {command: 'buy-advance A', features: {}, cashCost: 1, tokenCost: 0, group: 'advance.A', family: 'advance'},
            {command: 'pass', features: {'test.baseline': 1}, cashCost: 0, tokenCost: 0, group: 'pass', family: 'pass'},
        ]};
    return {actor, job: {observation, purchase, difficulty: 'HARD', seed: 0x6a09e667}};
}

test('actual compiled worker selector samples a server-shaped projection with no recommendation field', async () => {
    const {actor, job} = fixture();
    const run = createBotPolicyRunner(async () => ai, baseline, actor);

    assert.equal('baselineIndex' in job.purchase, false);
    assert.equal(await run(job), 'buy-advance A');
    assert.equal(ai.chooseBotMove(JSON.stringify(job.observation), JSON.stringify(baseline), 'HARD', job.seed), 'pass');
});

test('actual compiled worker keeps every unsupported setup on its exact ordinary choice', async () => {
    const {actor, job} = fixture();
    const run = createBotPolicyRunner(async () => ai, baseline, actor);
    const inputs = [
        {...job, difficulty: 'NORMAL'},
        {...job, difficulty: 'EASY'},
        {...job, observation: {...job.observation, dataVersion: 'ah1996-catalog-15'}},
        {...job, observation: {...job.observation, configuration: {...configuration, playerCount: 3}}},
        {...job, purchase: null},
    ];
    for (const input of inputs) {
        assert.equal(await run(input), ai.chooseBotMove(JSON.stringify(input.observation), JSON.stringify(baseline), input.difficulty, input.seed));
    }
});

test('ordinary choices survive missing or corrupted actor assets in the actual module', async () => {
    const {actor, job} = fixture();
    const invalid = {...actor, model: {...actor.model, stateWeights: []}};
    for (const asset of [null, {}, invalid, {...actor, baseline: {schemaVersion: 1, weights: {changed: 1}}}]) {
        const run = createBotPolicyRunner(async () => ai, baseline, asset);
        assert.equal(await run(job), 'pass');
    }
});

test('one cached actual actor tolerates a bad projection and handles the next valid job', async () => {
    const {actor, job} = fixture();
    const run = createBotPolicyRunner(async () => ai, baseline, actor);

    assert.equal(await run({...job, purchase: {...job.purchase, actions: [job.purchase.actions[1]]}}), 'pass');
    assert.equal(await run(job), 'buy-advance A');
});

test('unsupported ordinary rules remain errors rather than optional-actor fallbacks', async () => {
    const {actor, job} = fixture();
    const input = {...job, observation: {...job.observation, rulesVersion: 'future'}};
    const run = createBotPolicyRunner(async () => ai, baseline, actor);

    assert.throws(() => ai.chooseBotMove(JSON.stringify(input.observation), JSON.stringify(baseline), input.difficulty, input.seed), /Unsupported bot rules version/);
    await assert.rejects(run(input), /Unsupported bot rules version/);
});
