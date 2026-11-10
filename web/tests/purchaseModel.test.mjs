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
const sourcePolicySha256 = 'eaf40a734c2c48e4a38c7e088b16783f5eb0ba8f759ae4c25caabeab45793296';
const originalPolicySha256 = '3802440bc84830724e32c8f32f273648f38128fc7fc1774a9c586e5b1b1b2aa4';
const sourceBaselineSha256 = 'ca5a700523e29d5084bf754d6a19ff2051d618ee844ec8a617aee53701bed140';
const ordinaryModelSha256 = '0c24593a456816c9b6616b6ade2aa7b74ac3c41f0cbd0745b5b912675caa835b';
const frozenPurchasePriorSha256 = 'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0';

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
    assert.equal(digest(actor.model), 'eb410d4c9397934b5fa792e2ab91e146ae9c5de2ed70bc4480360f91219db5a4');
    assert.equal(actor.schemaVersion, 1);
    assert.equal(actor.actorVersion, 'aor-purchase-policy-1');
    assert.equal(actor.encoderVersion, 'aor-purchase-observation-1');
    assert.equal(actor.rulesVersion, 'ah1996-three-epoch-4');
    assert.equal(actor.dataVersion, 'ah1996-catalog-16');
    assert.deepEqual(actor.configuration, configuration);
    assert.equal(digest(actor.baseline), '53c26a55a7b7318e47d899847a1941a8e11db309ea34bd8c28486f92752e8cbe');
});

test('the installed hybrid binds its ordinary model and preserves the existing catalog policy', () => {
    const matching = baseline.models.filter(model => model.dataVersion === actor.dataVersion
        && model.rulesVersion === actor.rulesVersion
        && digest(model.configuration) === digest(configuration));

    assert.equal(matching.length, 1);
    assert.deepEqual(actor.ordinaryBaseline, {schemaVersion: matching[0].schemaVersion, weights: matching[0].weights});
    assert.equal(digest(actor.ordinaryBaseline), '40bb34483f84f7ef6bd9c46908e5b88bb3198c9d98ccc277c75d298c5ac63a0c');
    assert.notDeepEqual(actor.baseline, actor.ordinaryBaseline);
    assert(baseline.models.some(model => model.dataVersion === 'ah1996-catalog-15'),
        'Existing catalog-15 games must retain their qualified ordinary policy.');
});

test('the parent F1 hybrid retains all three independent purchase qualification gates', () => {
    const qualification = actor.provenance.parent.hybridQualification;

    assert.equal(qualification.qualificationPassed, true);
    assert.equal(qualification.humanStrengthVerified, false);
    assert.equal(qualification.qualificationConfidence, .99);
    assert.equal(qualification.ordinaryModelSha256, ordinaryModelSha256);
    assert.equal(qualification.purchasePolicySha256, sourcePolicySha256);
    assert.equal(qualification.frozenPurchasePriorSha256, frozenPurchasePriorSha256);
    assert.equal(qualification.incumbentPurchasePolicySha256, originalPolicySha256);
    const gates = [
        ['confirm', 2048, .114990234375, .0895116659495154, .105126953125, .08207292301143723],
        ['robustness', 4096, .0771484375, .05956481755909226, .07197265625, .056153642445138756],
        ['population', 4096, .0791015625, .06267220941649437, .07554524739583333, .06072486561152196],
    ];
    for (const [stage, count, winMean, winLower, rewardMean, rewardLower] of gates) {
        assert.equal(qualification[stage].policySha256, sourcePolicySha256);
        assert.equal(qualification[stage].minimumPairs, count);
        for (const [metric, mean, lower] of [
            ['winShareDifference', winMean, winLower],
            ['rewardDifference', rewardMean, rewardLower],
        ]) {
            assert.equal(qualification[stage][metric].count, count);
            assert.equal(qualification[stage][metric].qualificationConfidence, .99);
            assert.equal(qualification[stage][metric].mean, mean);
            assert.equal(qualification[stage][metric].lowerConfidence, lower);
            assert(qualification[stage][metric].lowerConfidence > 0);
        }
    }
});

test('the population gate preserves its resource stop and continuation of the same trial', () => {
    const population = actor.provenance.parent.hybridQualification.population;

    assert.deepEqual(population.recovery, {
        sourceCampaign: 'automata-purchase-population-20261110',
        originalStatus: 'resource-limit',
        reason: 'Campaign minimum free disk reserve reached.',
        reusedPairs: 3029,
        originalStartedEpoch: 1794308319.9742541,
        originalDeadlineEpoch: 1794319119.9742541,
        completedBeforeOriginalDeadline: true,
        resampled: false,
    });
    assert.deepEqual(population.canonicalReplays, {
        indices: [0, 1365, 2730, 4095], sides: ['candidate', 'incumbent'], verified: true,
    });
});

test('the shipped hybrid uses the frozen purchase prior for every signed seed', async () => {
    const prior = {...actor.baseline, rulesVersion: actor.rulesVersion, dataVersion: actor.dataVersion,
        configuration: actor.configuration};
    const legacyActor = {...actor};
    delete legacyActor.ordinaryBaseline;
    const frozen = createBotPolicyRunner(async () => ai, prior, legacyActor);
    const hybrid = createBotPolicyRunner(async () => ai, baseline, actor);

    for (const seed of [0, 1, 2, 17, -1, -2147483648, 2147483647, 0x6a09e667]) {
        const job = {...shipping, difficulty: 'HARD', seed};
        assert.equal(await hybrid(job), await frozen(job));
    }
});

test('the P48 provenance binds the continuation to its original actor and Adam state', () => {
    const provenance = actor.provenance.parent.provenance;

    assert.equal(provenance.trainingGames, 12288);
    assert.equal(provenance.selectedUpdate, 48);
    assert.deepEqual(provenance.continuation, {
        trainingGames: 4096,
        selectedBatch: 16,
        originalPolicySha256,
        optimizerFromSha256: '7c6df048edfceceb72cde3cf7dabdf94815fa0990bc1f831fa010c95ca9cec3e',
    });
    assert.equal(provenance.sourcePolicySha256, sourcePolicySha256);
    assert.equal(provenance.sourceBaselineSha256, sourceBaselineSha256);
    assert.equal(provenance.frozenPurchasePriorSha256, frozenPurchasePriorSha256);
    assert.equal(provenance.ordinaryModelSha256, ordinaryModelSha256);
    assert.equal(provenance.runnerCommit, 'bad819172fbe837576b3453dd80f11c938accf5e');
    assert.equal(provenance.nativeCommit, '5e9d5d951f5be1c86ea2c40e01607c41528e1d30');
    assert.deepEqual(provenance.nativeRuntimeSha256, {
        'lib/training.jar': '4fd044c8f902be470f6f9c57e83aa9ffc838cbadd8fa53fbb32c7d712e8e89b5',
        'lib/ai-jvm.jar': 'a9a144ebb563c52cc385f0d5af05054481cfda4b80d49a6d576f763fafe0ee7e',
    });
    assert.equal(provenance.qualificationPassed, true);
    assert.equal(provenance.humanStrengthVerified, false);
    assert.equal(new Set(provenance.evidence.map(item => item.path)).size, provenance.evidence.length);
    for (const evidence of provenance.evidence) assert.match(evidence.sha256, /^[a-f0-9]{64}$/);
    const expectedEvidence = {
        'automata-purchase-continuation-20261110/run/selected/policy.json': sourcePolicySha256,
        'automata-purchase-continuation-20261110/run/confirm-report.json':
            '6f5744e453dba2ce457957884169ca228da0545fef331fdd6a77d2ecf6e2cc99',
        'automata-purchase-continuation-20261110/run/robustness-report.json':
            '0fc1a8b35c84a2092b7f4c5ebdffb4e5ca2d49d45336fec5985f42a08a5d3440',
        'automata-purchase-population-20261110/run/campaign-state.json':
            '7a7d4b6929c529b34a89cd4a698156df41a9a814a69ca6e32513f8539c378570',
        'automata-purchase-population-20261110/recovery/resume-1/checkpoint.json':
            '39600f0398556f625dd842565ff550625979d2b97be6f973effc061e7a5b62d3',
        'automata-purchase-population-20261110/recovery/resume-1/run/population-report.json':
            '35ebc23466cdb4958a298bc0c12165a69e03e3d110d02ea5f2c26cda2823ab18',
        'automata-purchase-preview-20261110/browser-parity-result.json':
            '882925ddb64a9bd7ed00341cd8f6d2cb754fc4fee3022396dc72f735f4a199bd',
    };
    for (const [path, sha256] of Object.entries(expectedEvidence)) {
        assert.equal(provenance.evidence.find(item => item.path === path)?.sha256, sha256);
    }
});

test('the original P32 and F1 hybrid qualification remain intact in the parent lineage', () => {
    const provenance = actor.provenance.parent.provenance.parent.provenance;

    assert.equal(digest(provenance), '2110e6eff00783dd402da9df01db907eaf77980c7942cd160adf6d35be597f29');
    assert.equal(digest(actor.provenance.parent.provenance.parent.hybridQualification),
        'a756c1495075275af365c7591a2e4e3860918b94b811ebe75c9af5b5b3fbe9d4');
    assert.equal(provenance.trainingGames, 8192);
    assert.equal(provenance.selectedUpdate, 32);
    assert.equal(provenance.sourcePolicySha256, originalPolicySha256);
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

test('the shipped actor falls back when its ordinary model binding no longer matches', async () => {
    const job = {...shipping, difficulty: 'HARD', seed: 0x6a09e667};
    const mismatched = {...actor, ordinaryBaseline: {...actor.ordinaryBaseline,
        weights: {...actor.ordinaryBaseline.weights, cash: actor.ordinaryBaseline.weights.cash + 1}}};
    const run = createBotPolicyRunner(async () => ai, baseline, mismatched);
    const ordinary = ai.chooseBotMove(JSON.stringify(job.observation), JSON.stringify(baseline), job.difficulty, job.seed);

    assert.equal(ordinary, 'upgrade-ships');
    assert.equal(await run(job), ordinary);
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
