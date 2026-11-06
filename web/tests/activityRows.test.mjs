import assert from 'node:assert/strict';
import {test} from 'node:test';
import {activityRows} from '../src/activityRows.ts';

const first = {id: '1:0', kind: 'ROLL', detail: 'SHORTAGE', turnNumber: 2, stage: 'INCOME',
    dice: [{color: 'WHITE', value: 1}, {color: 'BLACK', value: 1}, {color: 'GREEN', value: 2}],
    marketRoll: {total: 2, commodities: ['STONE'], adjustment: 'SHORTAGE', cancelled: []}};
const second = {...first, id: '1:1', cashChanges: [{player: 'p1', amount: -2}],
    marketRoll: {...first.marketRoll, adjustment: 'SURPLUS', cancelled: ['STONE']}};

test('neighboring market rolls share one activity row with their recorded facts', () => {
    const rows = activityRows([first, second]);
    assert.deepEqual(rows, [[first, second]]);
    assert.equal(rows[0][0], first);
    assert.equal(rows[0][1], second);
});

test('appending a second roll preserves the original event and row identity', () => {
    const earlier = activityRows([first]);
    const later = activityRows([first, second]);
    assert.equal(later[0][0], earlier[0][0]);
    assert.equal(later[0][0].id, earlier[0][0].id);
});

test('market rolls from different turns keep separate activity rows', () => {
    const later = {...second, turnNumber: first.turnNumber + 1};
    assert.deepEqual(activityRows([first, later]), [[first], [later]]);
});

test('market rolls from different stages keep separate activity rows', () => {
    const later = {...second, stage: 'DRAW_CARD'};
    assert.deepEqual(activityRows([first, later]), [[first], [later]]);
});

test('an intervening action prevents grouping even when it is hidden in Activity', () => {
    const pass = {id: '1:2', kind: 'PASS'};
    assert.deepEqual(activityRows([first, pass, second]), [[first], [pass], [second]]);
});

test('other rolls retain their individual activity rows', () => {
    const competition = {...second, marketRoll: null, detail: 'COMPETITION'};
    assert.deepEqual(activityRows([first, competition]), [[first], [competition]]);
});

const purchase = {id: '10:0', kind: 'BUY_CARD', actor: 'p1', turnNumber: 2, stage: 'BUY_CARD'};
const draw = {...purchase, id: '10:1', kind: 'DRAW_CARD', drawReason: 'URBAN_ASCENDANCY', card: 'stone-1'};

test('a purchased card stays with its action without merging the next purchase', () => {
    const nextPurchase = {...purchase, id: '11:0'};
    assert.deepEqual(activityRows([purchase, draw, nextPurchase]), [[purchase, draw], [nextPurchase]]);
});

test('a draw in another revision does not imply that a purchase caused it', () => {
    const later = {...draw, id: '11:0'};
    assert.deepEqual(activityRows([purchase, later]), [[purchase], [later]]);
});

test('a competition action and result share a row with all original facts', () => {
    const attack = {...purchase, kind: 'ATTACK', stage: 'EXPANSION', provinces: ['florence']};
    const result = {...attack, id: '10:1', kind: 'COMPETITION_RESULT',
        competition: {attackerWon: false, defenders: ['p2', 'p3']}};
    assert.deepEqual(activityRows([attack, result]), [[attack, result]]);
});

test('another attacker or destination keeps a competition separate', () => {
    const attack = {...purchase, kind: 'PLACE', provinces: ['florence']};
    const result = {...attack, id: '10:1', kind: 'COMPETITION_RESULT'};
    for (const other of [{...result, actor: 'p2'}, {...result, provinces: ['rome']}]) {
        assert.deepEqual(activityRows([attack, other]), [[attack], [other]]);
    }
});

test('shortage rewards stay in causal order between the two market rolls', () => {
    const reward = {...first, id: '1:2', kind: 'DRAW_CARD', marketRoll: null,
        drawReason: 'SHORTAGE', actor: 'p1', card: 'stone-1'};
    assert.deepEqual(activityRows([first, reward, second]), [[first, reward, second]]);
});

test('a third market roll starts a new row', () => {
    const third = {...first, id: '1:3'};
    assert.deepEqual(activityRows([first, second, third]), [[first, second], [third]]);
});

test('revealed capital bids share a row but the tie roll remains distinct', () => {
    const bid = {...purchase, kind: 'BID_CAPITAL', amount: 5};
    const other = {...bid, id: '10:1', actor: 'p2', amount: 5};
    const tie = {...bid, id: '10:2', kind: 'ROLL', detail: 'CAPITAL_TIE'};
    assert.deepEqual(activityRows([bid, other, tie]), [[bid, other], [tie]]);
});

test('initial deals group across players without merging the regular turn draw', () => {
    const initial = {...draw, drawReason: 'INITIAL_DEAL'};
    const other = {...initial, id: '10:2', actor: 'p2', card: null};
    const regular = {...other, id: '10:3', drawReason: 'TURN_DRAW'};
    assert.deepEqual(activityRows([initial, other, regular]), [[initial, other], [regular]]);
});

test('Civil War order receipt stays with its cause but income keeps its own cause', () => {
    const action = {...purchase, kind: 'CIVIL_WAR_LOSS'};
    const order = {...purchase, id: '10:0:civil_war_order', kind: 'ORDER_CHANGED', actor: null};
    const income = {...order, id: '10:0:income', kind: 'INCOME'};
    assert.deepEqual(activityRows([action, order, income]), [[action, order], [income]]);
});

test('a Cathedral response resolves the immediately preceding competition across revisions', () => {
    const attack = {...purchase, kind: 'ATTACK', stage: 'EXPANSION', provinces: ['florence']};
    const cathedral = {...attack, id: '11:0', kind: 'COMPETITION_RESULT', detail: 'CATHEDRAL',
        competition: {attackerWon: false, defenders: ['p2']}};
    assert.deepEqual(activityRows([attack, cathedral]), [[attack, cathedral]]);
});

test('a card offer and the opposing player’s random trade stay together', () => {
    const offer = {...purchase, kind: 'OFFER_CARD', card: 'stone-1'};
    const trade = {...offer, id: '10:1', kind: 'TRADE_CARD', actor: 'p2', target: offer.actor, card: null};
    assert.deepEqual(activityRows([offer, trade]), [[offer, trade]]);
});

test('another recipient’s trade remains a separate action', () => {
    const offer = {...purchase, kind: 'OFFER_CARD', card: 'stone-1'};
    const trade = {...offer, id: '10:1', kind: 'TRADE_CARD', actor: 'p2', target: 'p3', card: null};
    assert.deepEqual(activityRows([offer, trade]), [[offer], [trade]]);
});

test('War play and its roll stay together without absorbing the province cession', () => {
    const play = {...purchase, kind: 'PLAY_CARD', card: 'war'};
    const roll = {...play, id: '10:1', kind: 'WAR_RESULT'};
    const cede = {...play, id: '11:0', kind: 'CEDE_WAR'};
    assert.deepEqual(activityRows([play, roll, cede]), [[play, roll], [cede]]);
});
