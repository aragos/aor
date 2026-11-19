import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {test} from 'node:test';
import {chooseBotMove} from '../../ai/build/dist/js/productionLibrary/aor-ai.mjs';

// Exercise the same generated module imported by the real worker, after the AI distribution build.
const shipped = JSON.parse(readFileSync(new URL('../src/ai/model.json', import.meta.url), 'utf8'));
const legacy = (shipped.models ?? [shipped]).find(model => model.dataVersion === 'ah1996-catalog-15');
assert(legacy, 'The bundle must retain the existing catalog-15 policy for existing games.');

const configuration = {
    playerCount: 4,
    map: 'STANDARD',
    initialCard: 'NO_CRUSADES',
    deckModification: true,
    immediateMasterArtDiscard: true,
};
const qualifiedFixture = {
    schemaVersion: 1,
    rulesVersion: 'ah1996-three-epoch-4',
    dataVersion: 'ah1996-catalog-16',
    configuration,
    weights: {cash: -1},
};
const fixtureBundle = {schemaVersion: 1, models: [qualifiedFixture, legacy]};

function observation(config = configuration, dataVersion = 'ah1996-catalog-16') {
    return {
        schemaVersion: 1,
        playerId: 'p1',
        decision: 'test',
        context: {},
        availableCash: 10,
        availableTokens: 0,
        rulesVersion: 'ah1996-three-epoch-4',
        dataVersion,
        configuration: config,
        candidates: [
            {command: 'cash-choice', features: {cash: 10}, cashCost: 0, tokenCost: 0, group: 'cash', family: 'other'},
            {command: 'points-choice', features: {points: 9.5}, cashCost: 0, tokenCost: 0, group: 'points', family: 'other'},
        ],
    };
}

function choose(input, model, difficulty = 'NORMAL') {
    return chooseBotMove(JSON.stringify(input), JSON.stringify(model), difficulty, 0);
}

test('the generated worker module activates an exactly qualified fixture model', () => {
    assert.equal(choose(observation(), fixtureBundle), 'points-choice');
    assert.equal(choose(observation(), {}), 'cash-choice');
});

test('the generated worker module retains the prior outside a qualified configuration', () => {
    const input = observation({...configuration, playerCount: 3});

    assert.equal(choose(input, fixtureBundle), 'cash-choice');
});

test('the actual shipped legacy policy remains usable for its existing catalog', () => {
    const input = observation(configuration, 'ah1996-catalog-15');

    assert.equal(choose(input, fixtureBundle), choose(input, legacy));
    assert.equal(choose(input, legacy), 'points-choice');
    assert.equal(choose(input, {}), 'cash-choice');
});

test('the actual shipped asset retains the prior outside the current qualified profile', () => {
    const input = observation({...configuration, playerCount: 3});

    assert.equal(choose(input, shipped), choose(input, {}));
});

test('the originally qualified models retain their exact setup and catalog', () => {
    const current = shipped.models.filter(model => model.dataVersion === 'ah1996-catalog-16' && model.configuration);

    assert.deepEqual(current.map(model => model.rulesVersion),
        ['ah1996-three-epoch-5', 'ah1996-three-epoch-4']);
    for (const model of current) {
        assert.deepEqual({drawThreeKeepTwo: false, ...model.configuration},
            {drawThreeKeepTwo: false, ...configuration});
    }
});

test('the broad model applies to Hard bots across supported player counts', () => {
    const broad = shipped.models.filter(model =>
        model.rulesVersion === 'ah1996-three-epoch-5' && model.dataVersion === 'ah1996-catalog-16' &&
        !model.configuration);
    assert.equal(broad.length, 1);
    assert.equal(broad[0].difficulty, 'HARD');

    for (const playerCount of [3, 4, 5, 6]) {
        const input = observation({...configuration, playerCount, map: 'EUROPEAN',
            initialCard: 'NO_EVENTS', drawThreeKeepTwo: true});
        input.rulesVersion = broad[0].rulesVersion;
        input.candidates[0].features = {};
        input.candidates[1].features = {'count.6.action.cathedral': -1};

        assert.equal(choose(input, shipped, 'HARD'), 'points-choice');
        assert.equal(choose(input, shipped, 'NORMAL'), 'cash-choice');
    }
});

test('the current scoped model retains priority in its exact setup', () => {
    const scoped = shipped.models.find(model => model.configuration &&
        model.rulesVersion === 'ah1996-three-epoch-5');
    const input = observation(configuration);
    input.rulesVersion = scoped.rulesVersion;
    input.candidates[0].features = {};
    input.candidates[1].features = {'count.6.action.cathedral': -1};

    assert.equal(choose(input, shipped, 'HARD'), choose(input, scoped, 'HARD'));
});

test('the actual shipped current model changes choices in its qualified setup', () => {
    const input = observation();
    input.candidates[1].features.points = 10.5;

    assert.equal(choose(input, shipped), 'cash-choice');
    assert.equal(choose(input, {}), 'points-choice');
});

test('older supported catalogs still receive a playable prior', () => {
    const input = observation(configuration, 'ah1996-catalog-13');

    assert.equal(choose(input, fixtureBundle), 'cash-choice');
});
