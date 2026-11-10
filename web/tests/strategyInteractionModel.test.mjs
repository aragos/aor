import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {chooseBotMove} from '../../ai/build/dist/js/productionLibrary/aor-ai.mjs';

const ordinary = JSON.parse(readFileSync(new URL('../src/ai/model.json', import.meta.url), 'utf8'));
const actor = JSON.parse(readFileSync(new URL('../src/ai/purchaseModel.json', import.meta.url), 'utf8'));
const f3 = ordinary.models.find(model => model.dataVersion === 'ah1996-catalog-16');
const candidateSha256 = '9d8b9cb3aa135474a49af41a1255e741604cb5cf4e9c9222eb7cd8dcd781ff92';
const incumbentSha256 = '0c24593a456816c9b6616b6ade2aa7b74ac3c41f0cbd0745b5b912675caa835b';
const purchaseSha256 = 'eaf40a734c2c48e4a38c7e088b16783f5eb0ba8f759ae4c25caabeab45793296';

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

test('the actual generated policy applies the qualified F3 commodity preference', () => {
    const previousOption = 8.078953647793742;
    const midpoint = (previousOption + f3.weights['planning.commodityOption']) / 2;
    const observation = {
        schemaVersion: 1, playerId: 'p1', decision: 'test', context: {},
        availableCash: 10, availableTokens: 0,
        rulesVersion: f3.rulesVersion, dataVersion: f3.dataVersion, configuration: f3.configuration,
        candidates: [
            {command: 'commodity-option', features: {'planning.commodityOption': 1},
                cashCost: 0, tokenCost: 0, group: 'commodity', family: 'other'},
            {command: 'cash-option', features: {cash: midpoint / f3.weights.cash},
                cashCost: 0, tokenCost: 0, group: 'cash', family: 'other'},
        ],
    };
    const previous = {...f3, weights: {...f3.weights, 'planning.commodityOption': previousOption}};
    const choose = model => chooseBotMove(JSON.stringify(observation), JSON.stringify(model), 'NORMAL', 0);

    assert.equal(f3.weights['planning.commodityOption'], 9.69474437735249);
    assert.equal(choose(ordinary), 'commodity-option');
    assert.equal(choose(previous), 'cash-option');
});

test('the bound F3 and unchanged P48 actor have direct independent interaction qualification', () => {
    const qualification = actor.hybridQualification;

    assert.equal(actor.provenance.ordinaryModelSha256, candidateSha256);
    assert.equal(qualification.ordinaryModelSha256, candidateSha256);
    assert.equal(qualification.incumbentOrdinaryModelSha256, incumbentSha256);
    assert.equal(qualification.purchasePolicySha256, purchaseSha256);
    assert.equal(qualification.frozenPurchasePriorSha256,
        'c4dd1609c737151caadfe0cb85efe406cee84f02b801877af35d65128488bcb0');
    assert.equal(qualification.qualificationPassed, true);
    assert.equal(qualification.humanStrengthVerified, false);
    assert.equal(qualification.sourceCampaign, 'automata-p48-strategy-interaction-20261110');
    assert.deepEqual(qualification.scope, actor.configuration);
    const gates = [
        ['confirm', .0255126953125, .009882725342439891, .023665364583333334, .009606005620311447],
        ['robustness', .0162353515625, .0023676532008054772, .015226236979166667, .002688456708198535],
    ];
    for (const [stage, winMean, winLower, rewardMean, rewardLower] of gates) {
        const gate = qualification[stage];
        assert.equal(gate.candidateSha256, candidateSha256);
        assert.equal(gate.incumbentSha256, incumbentSha256);
        assert.equal(gate.minimumPairs, 4096);
        assert.deepEqual(gate.canonicalReplays, {
            indices: [0, 1365, 2730, 4095], sides: ['candidate', 'incumbent'], verified: true,
        });
        for (const [metric, mean, lower] of [
            ['winShareDifference', winMean, winLower], ['rewardDifference', rewardMean, rewardLower],
        ]) {
            assert.equal(gate[metric].count, 4096);
            assert.equal(gate[metric].mean, mean);
            assert.equal(gate[metric].qualificationConfidence, .99);
            assert.equal(gate[metric].lowerConfidence, lower);
            assert(gate[metric].lowerConfidence > 0);
        }
        assert.equal(actor.provenance.evidence.find(item => item.path.endsWith(`${stage}-report.json`))?.sha256,
            gate.reportSha256);
    }
});

test('the F1 and P48 qualification survives unchanged as parent provenance', () => {
    assert.equal(digest(actor.provenance.parent.provenance),
        'a7b7ac1fae82a35a238f74914a8c7796dd52b7e07d87c602b433d32a0002b335');
    assert.equal(digest(actor.provenance.parent.hybridQualification),
        '8e3515dbfd232691611f2d6695e5cbed67273ec393ea28bf5fcad24e8fb719d0');
    assert.equal(actor.provenance.parent.hybridQualification.ordinaryModelSha256, incumbentSha256);
    assert.equal(actor.provenance.parent.hybridQualification.purchasePolicySha256, purchaseSha256);
});
