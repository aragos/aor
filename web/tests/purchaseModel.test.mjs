import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import * as ai from '../../ai/build/dist/js/productionLibrary/aor-ai.mjs';
import {createBotPolicyRunner} from '../src/ai/botPolicy.ts';

const baseline = JSON.parse(readFileSync(new URL('../src/ai/model.json', import.meta.url), 'utf8'));
const actor = JSON.parse(readFileSync(new URL('../src/ai/purchaseModel.json', import.meta.url), 'utf8'));
const configuration = {
    playerCount: 4,
    map: 'STANDARD',
    initialCard: 'NO_CRUSADES',
    deckModification: true,
    immediateMasterArtDiscard: true,
};
const sourcePolicySha256 = '3802440bc84830724e32c8f32f273648f38128fc7fc1774a9c586e5b1b1b2aa4';
const sourceBaselineSha256 = 'ca5a700523e29d5084bf754d6a19ff2051d618ee844ec8a617aee53701bed140';

// Object keys are sorted recursively; array order and parsed Float64 values are preserved.
function canonical(value) {
    if (Array.isArray(value)) return value.map(canonical);
    if (value !== null && typeof value === 'object') {
        return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
    }
    return value;
}

function digest(value) {
    return createHash('sha256').update(JSON.stringify(canonical(value))).digest('hex');
}

// Captured from a real creator-authorized server job. Only ordinary actor-safe inputs are retained;
// lease IDs, receipt IDs, credentials and game identity are not part of this fixture.
const shipping = JSON.parse(readFileSync(new URL('./fixtures/purchaseShipping.json', import.meta.url), 'utf8'));

test('the shipped purchase actor contains exactly the independently qualified parameters', () => {
    assert(actor, 'The qualified purchase actor must be installed in the browser asset.');
    assert.equal(digest(actor.model), 'fc76c3db46259efa96724053e45fca9ac36eae88acf68cafc2d839f522867893');
    assert.equal(actor.schemaVersion, 1);
    assert.equal(actor.actorVersion, 'aor-purchase-policy-1');
    assert.equal(actor.encoderVersion, 'aor-purchase-observation-1');
    assert.equal(actor.rulesVersion, 'ah1996-three-epoch-4');
    assert.equal(actor.dataVersion, 'ah1996-catalog-16');
    assert.deepEqual(actor.configuration, configuration);
});

test('the installed actor binds its qualified baseline and preserves the existing catalog policy', () => {
    const matching = baseline.models.filter(model => model.dataVersion === actor.dataVersion
        && model.rulesVersion === actor.rulesVersion
        && digest(model.configuration) === digest(configuration));

    assert.equal(matching.length, 1);
    assert.deepEqual(actor.baseline, {schemaVersion: matching[0].schemaVersion, weights: matching[0].weights});
    assert(baseline.models.some(model => model.dataVersion === 'ah1996-catalog-15'),
        'Existing catalog-15 games must retain their qualified ordinary policy.');
});

test('the actual asset records exact training and independent qualification provenance', () => {
    const provenance = actor.provenance;

    assert.equal(provenance.trainingGames, 8192);
    assert.equal(provenance.selectedUpdate, 32);
    assert.equal(provenance.sourcePolicySha256, sourcePolicySha256);
    assert.equal(provenance.sourceBaselineSha256, sourceBaselineSha256);
    assert.equal(provenance.trainingCommit, 'c4239ee8cdcbb285e8025f1dcd475722d3068497');
    assert.equal(provenance.runtimeQualificationCommit, 'ca641a43cc453d14c40667992873d0b9b5e8bb2a');
    assert.equal(provenance.signalProtocolSha256, 'c544d32a4b17b5a35ca84348c6ba151d829bbc25fdfe5c4a1bae00e743099454');
    assert.equal(provenance.learnedRivalProtocolSha256, '658bfb67e37a20b353ff3469ec3d476370bdb304f7439cad806f26fd462d3c7b');
    assert.equal(provenance.qualificationPassed, true);
    assert.equal(provenance.humanStrengthVerified, false);
    assert.deepEqual(provenance.evidence.map(item => item.path), [
        'signal-v1/confirmation.json',
        'signal-analysis-v1/final.json',
        'purchase-stress-v1/result.json',
        'purchase-stress-analysis-v1/final.json',
        'playable-parity-v1/candidate-runtime-verification.json',
    ]);
    for (const evidence of provenance.evidence) assert.match(evidence.sha256, /^[a-f0-9]{64}$/);
    assert.equal(provenance.evidence.find(item => item.path === 'signal-analysis-v1/final.json').sha256,
        'a29dcacc8a1f50a4c24304f873831952d8b27573f4bb66c31cde979cb09d0309');
    assert.equal(provenance.evidence.find(item => item.path === 'playable-parity-v1/candidate-runtime-verification.json').sha256,
        '2ecea6bf18880685bdfe19af86ab131349b3e1d212ea6f7269421d37795b781b');
});

test('the actual generated worker activates the shipped actor on an authoritative purchase', async () => {
    const job = {...shipping, difficulty: 'HARD', seed: 0x6a09e667};
    const run = createBotPolicyRunner(async () => ai, baseline, actor);
    const ordinary = ai.chooseBotMove(JSON.stringify(job.observation), JSON.stringify(baseline), job.difficulty, job.seed);

    assert.equal('baselineIndex' in job.purchase, false);
    assert.deepEqual(job.purchase.actions.map(action => action.command).sort(),
        job.observation.candidates.map(candidate => candidate.command).sort());
    assert.equal(ordinary, 'upgrade-ships');
    // This seed maps to actor uniform zero: the low-probability legal alternative proves activation.
    assert.equal(await run(job), 'pass');
});

test('the shipped actor retains exact ordinary choices outside its qualified use', async () => {
    const job = {...shipping, difficulty: 'HARD', seed: 0x6a09e667};
    const run = createBotPolicyRunner(async () => ai, baseline, actor);
    const inputs = [
        {...job, difficulty: 'NORMAL'},
        {...job, difficulty: 'EASY'},
        {...job, purchase: null},
        {...job, observation: {...job.observation, configuration: {...configuration, playerCount: 3}}},
        {...job, observation: {...job.observation, dataVersion: 'ah1996-catalog-15'}},
    ];

    for (const input of inputs) {
        const ordinary = ai.chooseBotMove(JSON.stringify(input.observation), JSON.stringify(baseline), input.difficulty, input.seed);
        assert.equal(await run(input), ordinary);
    }
});
