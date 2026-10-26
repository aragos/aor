// Illustrative UI fixtures only. The application must obtain offers and previews from the engine.
const players = [
    {id: 'barcelona', name: 'Barcelona', cash: 58, income: 39, misery: 20, fleet: 'Galley 4', cards: 3},
    {id: 'venice', name: 'Venice', cash: 81, income: 105, misery: 40, fleet: 'Seaworthy 10', cards: 4},
    {id: 'genoa', name: 'Genoa', cash: 36, income: 69, misery: 10, fleet: 'Galley 6', cards: 2},
    {id: 'paris', name: 'Paris', cash: 72, income: 69, misery: 50, fleet: 'Galley 2', cards: 5},
    {id: 'london', name: 'London', cash: 44, income: 69, misery: 30, fleet: 'Seaworthy 12', cards: 3},
    {id: 'hamburg', name: 'Hamburg', cash: 63, income: 69, misery: 0, fleet: 'Galley 4', cards: 2},
];
const identities = {
    barcelona: ['#b84d36', 'M-22 -22H22V1Q20 17 0 26Q-20 17 -22 1Z'],
    venice: ['#187d7f', 'M0 -24L23 0L0 24L-23 0Z'],
    genoa: ['#365eb1', 'M23 0A23 23 0 1 1 -23 0A23 23 0 1 1 23 0Z'],
    paris: ['#7950a4', 'M-13 -23H13L26 0L13 23H-13L-26 0Z'],
    london: ['#8b6d16', 'M-22 -22H22V22H-22Z'],
    hamburg: ['#3b7541', 'M0 -26L25 21H-25Z'],
};
const iconPaths = {
    coins: '<circle cx="11" cy="11" r="7"/><path d="M10 7h3M9 10h4M9 13h4M11 7v7M17 7a7 7 0 0 1-1 13"/>',
    income: '<path d="M4 19h16M5 15l5-5 4 3 6-9M14 4h6v6"/>',
    misery: '<circle cx="12" cy="12" r="9"/><path d="M8 9h1m6 0h1M8 17q4-6 8 0"/>',
    ship: '<path d="M3 16h18l-4 5H7ZM12 3v13M10 4L4 13h6M14 6l6 7h-6"/>',
    cards: '<rect x="7" y="3" width="13" height="17" rx="2"/><path d="M4 6H2v15a1 1 0 0 0 1 1h13M11 8h5M11 12h5"/>',
    token: '<rect x="4" y="4" width="16" height="16" rx="3"/><path d="M8 9h8M12 9v8"/>',
    marker: '<path d="M5 3h14v10q0 5-7 9-7-4-7-9Z"/>',
    leader: '<path d="M3 8l4 4 5-9 5 9 4-4-3 12H6ZM7 23h10"/>',
    war: '<path d="M4 3l13 13M3 4l13 13M14 19l5-5M17 19l4 3M21 3L8 16M8 19l-3-5M7 19l-4 3"/>',
};
const commodityStyles = {
    Wine: ['#edbfd0', '#893453', 'M3 1H15V7Q15 14 9 14Q3 14 3 7ZM9 14V20M4 20H14'],
    Wool: ['#f4dfb9', '#83551c', 'M18 10A9 8 0 1 1 0 10A9 8 0 1 1 18 10M3 4Q15 7 16 14M1 8Q12 9 13 17M6 2Q11 8 5 17'],
    Silk: ['#d9c4ed', '#70439a', 'M2 2Q7 6 15 2L18 18Q9 14 5 19ZM7 4L10 16M12 4L14 16'],
    Grain: ['#f3d575', '#735313', 'M9 2V20M9 6L3 2M9 10L2 6M9 14L2 10M9 6L15 2M9 10L16 6M9 14L16 10'],
    Metal: ['#b9cddb', '#365b75', 'M2 18L6 3H13L17 18ZM7 15L9 7M12 7L14 15'],
    Timber: ['#bedbb7', '#37683c', 'M9 0L2 7H5L0 13H6V20H12V13H18L13 7H16Z'],
    Ivory: ['#eee4bc', '#746332', 'M5 1Q20 9 6 20Q14 10 5 1Z'],
    Stone: ['#d7d5ca', '#626152', 'M1 6L5 1H14L18 7V19H1ZM1 10H18M7 10V19M12 1V10'],
    Cloth: ['#bcded9', '#2d6d68', 'M1 2H17V19H1ZM1 8H17M1 14H17M6 2V19M12 2V19'],
    Fur: ['#d6bca7', '#775036', 'M5 1L9 4L13 1L18 6L14 9L15 16L12 20L9 17L6 20L3 16L4 9L0 6Z'],
    Gold: ['#edbf5c', '#8b6312', 'M1 18L5 6H14L18 18ZM5 6L8 2H15L14 6M18 18L20 13L15 2'],
    Spice: ['#ebae91', '#963c25', 'M4 2H14L12 7Q20 15 14 20H4Q-2 15 6 7ZM5 7H13M7 12H11M7 16H11'],
};
// Counts and example payouts follow the player order above. Payouts are fixture values, not a rules implementation.
const commodities = [
    {name: 'Wine', counts: [1, 3, 2, 0, 0, 0], adjustment: 'Shortage', quantity: 1, payouts: [20, 80, 45, 0, 0, 0]},
    {name: 'Wool', counts: [0, 2, 1, 2, 1, 0], payouts: [0, 8, 2, 8, 2, 0]},
    {name: 'Silk', counts: [0, 1, 0, 1, 1, 0], payouts: [0, 8, 0, 8, 8, 0]},
    {name: 'Grain', counts: [1, 2, 1, 1, 2, 1], adjustment: 'Surplus', quantity: 1, payouts: [0, 4, 0, 0, 4, 0]},
    {name: 'Cloth', counts: [1, 0, 2, 1, 1, 1], payouts: [5, 0, 20, 5, 5, 5]},
    {name: 'Metal', counts: [0, 2, 1, 0, 1, 2], payouts: [0, 24, 6, 0, 6, 24]},
    {name: 'Timber', counts: [1, 1, 0, 1, 0, 2], payouts: [3, 3, 0, 3, 0, 12]},
    {name: 'Stone', counts: [0, 0, 1, 2, 1, 1], payouts: [0, 0, 1, 4, 1, 1]},
    {name: 'Fur', counts: [0, 0, 0, 1, 1, 2], payouts: [0, 0, 0, 7, 7, 28]},
    {name: 'Spice', counts: [0, 2, 1, 0, 0, 0], payouts: [0, 36, 9, 0, 0, 0]},
    {name: 'Gold', counts: [0, 1, 0, 0, 1, 0], payouts: [0, 10, 0, 0, 10, 0]},
    {name: 'Ivory', counts: [0, 1, 0, 0, 0, 0], payouts: [0, 10, 0, 0, 0, 0]},
];
const state = {study: 'table', view: 'map', selected: '', commodity: 'Wine', placed: 0, zoom: 1};
const expansion = {pool: 7, markers: 21, incomeAfterMarker: 45};
const leaderDiscounts = [
    {advance: 'Human Body', amount: 30},
    {advance: 'Master Art', amount: 30},
    {advance: 'Renaissance', amount: 30},
];
const yourAdvances = [
    {category: 'Science', owned: ['Laws of Matter'], total: 4},
    {category: 'Religion', owned: [], total: 4},
    {category: 'Commerce', owned: ['Caravan', 'Wind/Watermill', 'Improved Agriculture'], total: 5},
    {category: 'Communication', owned: ['Written Record'], total: 4},
    {category: 'Exploration', owned: ['Overland East'], total: 4},
    {category: 'Civics', owned: ['Urban Ascendancy', 'Nationalism'], total: 5},
];
const $ = selector => document.querySelector(selector);
const $$ = selector => [...document.querySelectorAll(selector)];
const icon = name => `<svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${iconPaths[name]}</svg>`;
function emblem(id, fresh = false) {
    const [color, path] = identities[id];
    return `<svg class="emblem" viewBox="-29 -29 58 58" aria-hidden="true"><path d="${path}" fill="${fresh ? '#fffdf4' : color}" stroke="${color}" stroke-width="3"/></svg>`;
}
function commodityIcon(name) {
    const [background, stroke, path] = commodityStyles[name];
    return `<svg class="commodity-icon" viewBox="0 0 28 28" aria-hidden="true"><circle cx="14" cy="14" r="14" fill="${background}"/><path d="${path}" transform="translate(5 4)" fill="none" stroke="${stroke}" stroke-width="1.6" stroke-linejoin="round"/></svg>`;
}
function owners(commodity) {
    return players.flatMap((player, index) => commodity.counts[index] > 0
        ? [{player, count: commodity.counts[index], payout: commodity.payouts[index]}] : []);
}
function payouts(commodity) {
    return `<span class="owner-payouts">${owners(commodity).map(({player, payout}) => `<span class="owner-payout ${payout === 0 ? 'zero' : ''}" aria-label="${player.name}: $${payout} payout" title="${player.name}: $${payout} payout">${emblem(player.id)}<strong>$${payout}</strong></span>`).join('')}</span>`;
}
function incomeFor(player) {
    return player.id === 'barcelona' && state.placed === 2 ? expansion.incomeAfterMarker : player.income;
}
function poolStatus() {
    return `<div class="pool-status" aria-label="${expansion.pool - state.placed} pool tokens available">${icon('token')}<strong>${expansion.pool - state.placed}</strong><span>Pool<br>tokens</span></div>`;
}
function adjustment(commodity) {
    return commodity.adjustment ? `<span class="adjustment ${commodity.adjustment.toLowerCase()}" title="${commodity.quantity} ${commodity.adjustment.toLowerCase()} markers" aria-label="${commodity.quantity} ${commodity.adjustment.toLowerCase()} markers">${commodity.adjustment === 'Shortage' ? '↑' : '↓'} ${commodity.quantity}</span>` : '';
}
function announce(message) { $('#status').textContent = message; }
function showDetails(title, content) {
    $('#detail-title').textContent = title;
    $('#detail-content').innerHTML = content;
    $('#detail-dialog').showModal();
}
function renderPlayers() {
    $('#players').innerHTML = players.map((player, index) => `<button class="player ${index === 0 ? 'active' : ''}" data-player="${player.id}" aria-label="${player.name}${index === 0 ? ', you, active player' : ''}. Cash ${player.cash}, current income ${incomeFor(player)}, misery box ${player.misery}, ${player.fleet}, ${player.cards} cards.">
        <span class="player-title">${emblem(player.id)}<span class="player-name">${player.name}</span><span class="player-rank">${index === 0 ? 'YOU' : `0${index + 1}`}</span></span>
        <span class="player-stats"><span title="Cash">${icon('coins')}<strong>$${player.cash}</strong></span><span class="player-income" title="Current income">${icon('income')}<strong>$${incomeFor(player)}</strong></span></span>
        <span class="player-stats"><span title="Current misery box value">${icon('misery')}<strong>${player.misery}</strong></span><span title="Current fleet">${icon('ship')} ${player.fleet}</span><span title="Cards in hand">${icon('cards')} ${player.cards}</span></span>
    </button>`).join('');
}
function renderResources() {
    $('#resources').innerHTML = `<p class="overline">Available to you</p><div class="resource-counts">
        <div><strong>$${players[0].cash}</strong><span>Cash</span></div></div>
        <details class="stock-details"><summary>Stock</summary><div class="balance-row"><span>Markers</span><strong>${expansion.markers - (state.placed === 2 ? 1 : 0)}</strong></div></details>`;
}
function renderHand() {
    const commodityValues = ['Wine', 'Cloth'].map(name => {
        const commodity = commodities.find(candidate => candidate.name === name);
        return `<span class="card-commodity-values"><span class="card-commodity-name">${commodityIcon(name)}${name}${adjustment(commodity)}</span>${payouts(commodity)}</span>`;
    }).join('');
    const cards = [
        {name: 'Wine / Cloth', kind: 'commodity', values: commodityValues},
        {name: 'Leonardo da Vinci', kind: 'leader', values: `<span class="card-discounts">${leaderDiscounts.map(discount => `<span><span>${discount.advance}</span><strong>−$${discount.amount}</strong></span>`).join('')}</span>`},
        {name: 'War', kind: 'event', values: `<span class="card-event-art">${icon('war')}</span><span class="card-targets"><span>Opponent</span><span>${players.slice(1).map(player => `<span title="${player.name}">${emblem(player.id)}</span>`).join('')}</span></span>`},
    ];
    $('#hand').innerHTML = cards.map(card => `<button class="hand-card ${card.kind}" data-card="${card.name}"><span class="card-kind">${card.kind}</span><strong class="card-title">${card.name}</strong>${card.values}</button>`).join('');
}
function renderMarkets() {
    $('#commodity-grid').innerHTML = commodities.map(commodity => `<button class="commodity-tile" data-commodity="${commodity.name}" aria-pressed="${state.commodity === commodity.name}">
        <span class="commodity-title">${commodityIcon(commodity.name)}${commodity.name}${adjustment(commodity)}</span>${payouts(commodity)}</button>`).join('');
}
function idleInspector() {
    return `<p class="overline">Your move</p><h2>Expansion</h2>${poolStatus()}
        <button class="destination-choice" id="choose-valencia"><span>${commodityIcon('Silk')} Valencia</span><strong>${state.placed === 2 ? '' : `${2 - state.placed} ${icon('token')} → `}${emblem('barcelona', true)}</strong></button>`;
}
function marketInspector() {
    if (state.selected === 'west-africa') {
        return `<p class="overline">Selected market</p><h2>West Africa</h2>
            <div class="province-meta">${commodityIcon('Ivory')} Ivory <span>·</span> Market 2</div>
            ${poolStatus()}
            <div class="warning"><strong>Access required</strong><br>The Heavens or Seaworthy Vessels is needed to reach West Africa.</div>
            <button class="primary" disabled>Establish marker</button>
            <p class="explanation">${icon('ship')} Galley 4</p>
            <button class="text-action" id="choose-valencia">Valencia →</button>`;
    }
    if (state.selected !== 'valencia') {
        const group = $(`[data-province-label="${state.selected}"]`);
        const name = group.querySelector('text').textContent;
        return `<p class="overline">Province details</p><h2>${name}</h2><p class="intro">${group.querySelector('title').textContent}</p>${poolStatus()}`;
    }
    if (state.placed === 2) {
        return `<p class="overline">Placement complete</p><h2>Valencia</h2><div class="province-meta">${commodityIcon('Silk')} Silk <span>·</span> Market 2</div>
            ${poolStatus()}<div class="balance-row"><span>${emblem('barcelona', true)} New marker</span><strong>Valencia</strong></div>
            <div class="balance-row"><span>Income</span><strong>$${incomeFor(players[0])}</strong></div>
            <div class="balance-row"><span>Silk payout</span><strong>$8</strong></div>
            <div class="balance-row"><span>Tokens returned to stock</span><strong>2</strong></div>
            <button class="text-action" id="reset-order">Reset preview</button>`;
    }
    const needed = 2 - state.placed;
    return `<p class="overline">Establish your marker</p><h2>Valencia</h2>
        <div class="province-meta">${commodityIcon('Silk')} Silk <span>·</span> Market 2 <span>·</span> Area IV</div>
        <p class="intro">${state.placed ? '1 of your tokens' : 'Empty'} · Land from Barcelona</p>
        <div class="pool-preview" aria-label="Pool tokens: ${expansion.pool - state.placed} now, 5 after placement"><span>Pool tokens</span><div><strong>${expansion.pool - state.placed}</strong><span>→</span><strong>5</strong></div></div>
        <div class="commitment"><div><strong>${icon('token')} ${needed}</strong><small>Pool tokens</small></div><span class="selection-arrow">→</span>
            <div><strong>${emblem('barcelona', true)}</strong><small>New marker</small></div></div>
        <div class="balance-row"><span>Income</span><strong>$${players[0].income} → $${expansion.incomeAfterMarker} <span class="gain">+$6</span></strong></div>
        <div class="balance-row"><span>Silk payout</span><strong>$0 → $8</strong></div>
        <button class="primary" id="establish">Establish marker · ${needed} token${needed === 1 ? '' : 's'}</button>
        ${state.placed ? '' : `<details><summary>Place fewer tokens</summary><div class="partial-row"><label for="partial-count">Tokens</label><input id="partial-count" type="number" value="1" min="1" max="1"><button id="place-partial">Place 1 token</button></div></details>`}
        <details><summary>Placement details</summary><div class="balance-row"><span>Markers</span><strong>${expansion.markers} → ${expansion.markers - 1}</strong></div><div class="balance-row"><span>Tokens returned to stock</span><strong>2</strong></div><div class="balance-row"><span>Shipping used</span><strong>0</strong></div></details>`;
}
function commodityInspector() {
    const commodity = commodities.find(candidate => candidate.name === state.commodity);
    return `<p class="overline">Selected commodity</p><div class="commodity-detail-heading">${commodityIcon(commodity.name)}<h2>${commodity.name}</h2></div>
        ${commodity.adjustment ? `<span class="pill">${commodity.adjustment === 'Shortage' ? '↑' : '↓'} ${commodity.quantity} ${commodity.adjustment.toLowerCase()} marker${commodity.quantity > 1 ? 's' : ''}</span>` : ''}
        <div class="payout-head"><span>Power</span><span>Payout</span></div>
        ${owners(commodity).map(({player, payout}) => `<div class="payout-row">${emblem(player.id)}<span>${player.name}</span><strong>$${payout}</strong></div>`).join('')}
        <details><summary>Ownership</summary>${owners(commodity).map(({player, count}) => `<div class="balance-row"><span>${emblem(player.id)} ${player.name}</span><strong>${count} market${count === 1 ? '' : 's'}</strong></div>`).join('')}</details>`;
}
function renderInspector() {
    $('#inspector').classList.toggle('idle', state.view === 'map' && !state.selected);
    const close = state.view === 'map' && state.selected ? '<button id="close-selection" class="inspector-close" aria-label="Close province details">×</button>' : '';
    $('#inspector').innerHTML = close + (state.view === 'markets' ? poolStatus() + commodityInspector() : state.selected ? marketInspector() : idleInspector());
}
function updateMap() {
    $$('#map-art [data-province]').forEach(group => {
        const selected = group.dataset.province === state.selected;
        group.classList.toggle('selected', selected);
        group.setAttribute('aria-pressed', String(selected));
    });
    const marker = $('#valencia-draft');
    marker.style.display = state.selected === 'valencia' || state.placed ? '' : 'none';
    marker.setAttribute('opacity', state.placed === 2 ? '1' : '.6');
    marker.querySelector('path').setAttribute('stroke-dasharray', state.placed ? '' : '5 4');
    marker.querySelector('text').textContent = state.placed === 1 ? '1' : '';
    const title = state.placed === 2 ? 'Valencia, market 2, silk. Your new marker.' : state.placed === 1 ? 'Valencia, market 2, silk. Your one new token.' : 'Valencia, empty market 2, silk. Select to establish your marker.';
    $('#map-art [data-province="valencia"]').setAttribute('aria-label', title);
    $('#map-art [data-province-label="valencia"] title').textContent = title;
}
function render() {
    $('.game').classList.toggle('has-selection', state.view === 'map' && Boolean(state.selected));
    $('#map-view').hidden = state.view !== 'map';
    $('#markets-view').hidden = state.view !== 'markets';
    $('#region-label').textContent = state.view === 'map' ? 'Iberia & northwest Africa' : 'Current payouts';
    $$('[data-view]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.view === state.view)));
    $$('[data-study]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.study === state.study)));
    const captions = {
        table: ['01 / The compact game table', 'Income joins the player overview. Pool tokens take priority during expansion; hand cards show payouts and discounts.'],
        markets: ['02 / Commodity payouts at a glance', 'Payouts take priority. Powers with no holdings are omitted; ownership counts are available in detail.'],
        action: ['03 / The usual action, ready to review', 'Pool tokens and the remaining commitment lead the decision. Income and commodity payout changes appear beside the action.'],
    };
    $('#study-title').textContent = captions[state.study][0];
    $('#study-caption').textContent = captions[state.study][1];
    renderMarkets(); renderResources(); renderInspector(); updateMap();
}
function selectMarket(id) {
    state.view = 'map'; state.selected = id; state.study = 'action'; render();
    focusMobileDecision();
    announce(id === 'valencia' ? state.placed === 2 ? 'Valencia selected. Your new marker is here.' : `Valencia selected. ${2 - state.placed} tokens needed to establish a marker.` : `${id.replaceAll('-', ' ')} selected.`);
}
function resetOrder() {
    state.placed = 0;
    commodities.find(commodity => commodity.name === 'Silk').counts[0] = 0;
    commodities.find(commodity => commodity.name === 'Silk').payouts[0] = 0;
    $('#recent-text').textContent = 'Genoa established a marker in Seville';
    $('#command-history').textContent = 'genoa: place 3 seville';
    renderPlayers(); renderHand(); render();
}
function placeTokens(partial) {
    const amount = partial ? 1 : 2 - state.placed;
    state.placed += amount;
    if (state.placed === 2) {
        commodities.find(commodity => commodity.name === 'Silk').counts[0] = 1;
        commodities.find(commodity => commodity.name === 'Silk').payouts[0] = 8;
    }
    const message = partial ? 'You placed 1 token in Valencia' : 'You established a marker in Valencia';
    $('#recent-text').textContent = message;
    $('#command-history').textContent += `\nbarcelona: place ${amount} valencia`;
    renderPlayers(); renderHand(); render(); announce(`${message}.`);
    $('#reset-order, #establish')?.focus();
}
function openConsole() {
    $('#command-dock').hidden = false;
    $('#command-input').focus();
}
function showCard(name) {
    if (name === 'Wine / Cloth') {
        showDetails(name, ['Wine', 'Cloth'].map(commodityName => {
            const commodity = commodities.find(candidate => candidate.name === commodityName);
            return `<section class="card-detail-commodity"><h3>${commodityIcon(commodityName)} ${commodityName} ${adjustment(commodity)}</h3>${owners(commodity).map(({player, payout}) => `<div class="payout-row">${emblem(player.id)}<span>${player.name}</span><strong>$${payout}</strong></div>`).join('')}</section>`;
        }).join(''));
        return;
    }
    if (name === 'Leonardo da Vinci') {
        showDetails(name, `<p class="overline">Discounts</p>${leaderDiscounts.map(discount => `<div class="discount-detail"><span>${discount.advance}</span><strong>−$${discount.amount}</strong></div>`).join('')}
            <details><summary>Discount details</summary><div class="balance-row"><span>Printed discount per advance</span><strong>$20</strong></div><div class="balance-row"><span>Written Record</span><strong>+$10</strong></div></details>`);
        return;
    }
    showDetails(name, `<p class="overline">Opponent</p><div class="opponents">${players.slice(1).map(player => `<span>${emblem(player.id)} ${player.name}</span>`).join('')}</div>`);
}
document.addEventListener('click', event => {
    const button = event.target.closest('button');
    if (!button) return;
    if (button.dataset.study) {
        state.study = button.dataset.study;
        state.view = state.study === 'markets' ? 'markets' : 'map';
        state.selected = state.study === 'action' ? 'valencia' : '';
        render();
        if (state.selected) focusMobileDecision();
    } else if (button.dataset.view) {
        state.view = button.dataset.view;
        state.study = state.view === 'markets' ? 'markets' : state.selected ? 'action' : 'table';
        render();
    } else if (button.dataset.commodity) {
        state.commodity = button.dataset.commodity; state.view = 'markets'; state.study = 'markets'; render();
        $(`[data-commodity="${state.commodity}"]`)?.focus({preventScroll: true});
        announce(`${state.commodity} selected. Payout details updated.`);
    } else if (button.dataset.player) {
        const player = players.find(candidate => candidate.id === button.dataset.player);
        showDetails(player.name, `<div class="detail-grid"><div class="detail-stat">Cash<strong>$${player.cash}</strong></div><div class="detail-stat">Current income<strong>$${incomeFor(player)}</strong></div><div class="detail-stat">Misery box value<strong>${player.misery}</strong></div><div class="detail-stat">Fleet<strong>${player.fleet}</strong></div><div class="detail-stat">Cards in hand<strong>${player.cards}</strong></div></div>`);
    } else if (button.dataset.card) {
        showCard(button.dataset.card);
    }
    switch (button.id) {
        case 'choose-valencia': selectMarket('valencia'); break;
        case 'blocked-destination': selectMarket('west-africa'); break;
        case 'establish': placeTokens(false); break;
        case 'place-partial': if ($('#partial-count').reportValidity()) placeTokens(true); break;
        case 'reset-order': resetOrder(); break;
        case 'close-selection': state.selected = ''; state.study = 'table'; render(); zoomMap(); $('#choose-valencia').focus({preventScroll: true}); break;
        case 'reset-demo': state.study = 'table'; state.view = 'map'; state.selected = ''; state.zoom = 1; resetOrder(); zoomMap(); break;
        case 'command-open': openConsole(); break;
        case 'command-close': $('#command-dock').hidden = true; $('#command-open').focus(); break;
        case 'detail-close': $('#detail-dialog').close(); break;
        case 'map-zoom-in': state.zoom = Math.min(2.5, state.zoom * 1.25); zoomMap(); break;
        case 'map-zoom-out': state.zoom = Math.max(1, state.zoom / 1.25); zoomMap(); break;
        case 'map-reset': state.zoom = 1; zoomMap(); break;
        case 'map-key-open': showDetails('Map key', `<p>Commodity colors identify permanent market resources. Each capital has one color and silhouette.</p><p>${emblem('barcelona', true)} White face: new marker. ${emblem('barcelona')} Filled face: established marker.</p><p>A number inside a piece counts tokens. Market numbers sit beside commodity symbols. A dark outline marks your selection.</p>`); break;
        case 'advances-open': showDetails('Your advances', yourAdvances.map(({category, owned, total}) => `<details class="advance-category"><summary>${category}<strong>${owned.length} / ${total}</strong></summary>${owned.length ? `<p>${owned.join(' · ')}</p>` : ''}</details>`).join('')); break;
        case 'history-open': showDetails('Recent activity', `<p>${emblem('genoa')} Genoa established a marker in Seville.</p><p>${commodityIcon('Wine')} Wine has one shortage marker.</p>${state.placed ? `<p>${emblem('barcelona', true)} You ${state.placed === 2 ? 'established a marker' : 'placed one token'} in Valencia.</p>` : ''}`); break;
    }
});
function zoomMap() {
    const width = 1280 / state.zoom, height = 830 / state.zoom;
    $('#map-art > svg').setAttribute('viewBox', `${660 - width / 2} ${625 - height / 2} ${width} ${height}`);
}
function focusMobileDecision() {
    if (!matchMedia('(max-width: 760px)').matches || !$('.game').classList.contains('has-selection')) return;
    $('.game').scrollIntoView({block: 'start'});
    if (state.selected === 'valencia') $('#map-art > svg').setAttribute('viewBox', '550 520 570 470');
}
matchMedia('(max-width: 760px)').addEventListener('change', event => {
    if (event.matches) requestAnimationFrame(focusMobileDecision); else zoomMap();
});
$$('#map-art [data-province]').forEach(group => {
    group.setAttribute('role', 'button'); group.setAttribute('tabindex', '0');
    group.setAttribute('aria-label', group.querySelector('title').textContent);
    group.addEventListener('click', () => selectMarket(group.dataset.province));
    group.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); selectMarket(group.dataset.province); }
    });
});
renderPlayers(); renderHand(); zoomMap(); render();
