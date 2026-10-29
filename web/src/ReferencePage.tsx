import {useEffect, useState} from 'react';
import {request} from './api';
import type {CardDefinition, PublicCatalog, Snapshot} from './api';
import {advanceName, label} from './labels';
import {t} from './messages';
import {CardEventText} from './CardEventText';
import {AdvanceBenefits} from './AdvanceBenefits';
import {Commodity} from './TablePanels';
import {AccessLock, PlayerPiece} from './map/MapSymbols';

interface ReferenceData {
    catalog: PublicCatalog;
    cards: CardDefinition[];
    leaders: {id: string; discounts: {advance: string; amount: number; condition: string | null; increasedAmount: number | null}[]}[];
    rules: Snapshot['game']['rules'];
}

const variants = [
    {key: 'map', title: 'Map', text: 'Standard map uses the original board. European map adds Loire for 4–6 players, removes Smolensk, changes eastern connections, and merges the Aegean into the Central Mediterranean. Crete touches only that combined sea on the European map. Fez touches the Canary Basin and Western Mediterranean on both maps.'},
    {key: 'deck-modification', title: 'Move Timber and War', text: 'When enabled, one epoch-2 Timber card moves to epoch 1 and War moves from epoch 1 to epoch 2. Other cards keep their usual decks.'},
    {key: 'initial-card', title: 'Opening card', text: 'Everyone receives one card before capital bidding. Choose any history card, no Crusades, no events, or commodities only. No events still permits leaders. Restrictions apply only to this opening deal; excluded cards remain available for later draws.'},
    {key: 'immediate-master-art-discard', title: 'Immediate Master Art discard', text: 'When enabled, buying Master Art offers its optional discard immediately, before the rest of that purchase activity. The regular Master Art discard opportunity remains available in the Buy Card phase.'},
];

/** Static public rules and catalog reference; it never loads a player's hand or game history. */
export default function ReferencePage() {
    const [reference, setReference] = useState<ReferenceData | null>(null);
    const [error, setError] = useState(false);
    useEffect(() => {
        let active = true;
        document.title = 'AoR · Help';
        void request<ReferenceData>('/api/reference').then(data => {if (active) setReference(data);})
            .catch(() => {if (active) setError(true);});
        return () => {active = false; document.title = 'AoR';};
    }, []);
    useEffect(() => {
        if (reference && window.location.hash) document.getElementById(window.location.hash.slice(1))?.scrollIntoView();
    }, [reference]);
    const catalog = reference?.catalog;
    const cardGroups = reference ? [...new Set(reference.cards.map(card => card.messageId))]
        .map(messageId => reference.cards.filter(card => card.messageId === messageId)) : [];
    return <main className="reference-page">
        <header><a href="/">AoR</a><h1>Playing AoR</h1><p>Functionality, rules reference, and the map key.</p></header>
        <nav aria-label="Help topics"><a href="#playing">Playing</a><a href="#variants">Variants</a>
            <a href="#map-legend">Map key</a><a href="#cards">Cards</a><a href="#advances">Advances</a>
            <a href="#commodities">Payout boxes</a><a href="#tracks">Tracks</a><a href="#history">Activity and commands</a></nav>
        <section id="playing"><h2>Playing and taking turns</h2>
            <p>Create a game with 3–6 players, choose variants and undo scope, and setup starts immediately. Every game uses three epochs. Local games provide private seat links; hosted games use registered accounts.</p>
            <p>The decision panel names the acting player and the current choice. Waiting players can inspect the board, cards, and advances. Inspection preserves a prepared action. The colorful shield favicon means you have an outstanding decision; grayscale means you are waiting. In the game list it reflects all your games, regardless of the selected filter.</p>
            <p>Choose an object to prepare an order, then confirm in the decision panel. Map target highlighting opens automatically for location cards. Clicking eligible provinces, coastal water, players, or area badges edits the same draft as the choice buttons. Nothing is committed until you confirm. Polling preserves drafts; a new decision clears obsolete ones. Rejected actions remain correctable.</p>
            <p>Capital bids and token purchases are private until everyone submits. Replace your submission before the batch reveals. Capital bids cost the amount bid. Token purchases cost the absolute amount; positive amounts buy tokens up to your available stock, while negative bids buy none.</p>
            <p>Turns proceed through token purchase and order, draws, Buy Card, Play Cards, purchases, expansion, and income. Nested decisions identify who must answer before the enclosing activity resumes. The final round requires playing all non-burden cards before scoring.</p>
            <p>Expansion defaults to filling a market. If pool or shipping limits prevent that, “Place maximum possible tokens” uses the largest legal partial commitment. Competition commitments are exact. White markers are new this turn; player summaries show their counts. Military advantages depend on the opposing cards.</p>
            <p>Urban Ascendancy owners can queue a $10 card purchase while waiting. This private browser draft requires the view to stay open, can be canceled, submits once when legal, and never retries a rejected purchase. It does not automatically pass a remaining decision.</p>
            <p>Purchases proceed through ships, advances, stabilization, and misery relief. Stabilization previews its destination box. A completed advance tier keeps relief explicit even when it is blocked; Pass finishes that step.</p>
        </section>
        <section id="variants"><h2>Game variants</h2>{variants.map(variant => <article id={`variant-${variant.key}`} key={variant.key}>
            <h3>{variant.title}</h3><p>{variant.text}</p></article>)}</section>
        <section id="map-legend"><h2>Map key and navigation</h2>
            <div className="reference-legend">
                <span><svg viewBox="0 0 32 33" width="28" height="28" aria-hidden="true"><rect width="32" height="33" rx="7" fill="#263c3c"/><text x="16" y="25" textAnchor="middle" fontSize="27" fill="#fffdf5">4</text></svg>Dark badge: capital market</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="barcelona" size={44}/></svg>Unnumbered silhouette: domination marker</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="venice" count={2} size={44}/></svg>Numbered silhouette: token count</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="barcelona" fresh size={44}/></svg>White face: new this turn; filled face: established</span>
                <span><svg viewBox="0 0 24 24" width="28" height="28" aria-hidden="true"><AccessLock/></svg>Lock: an advance is required</span>
                <span><svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true"><defs><pattern id="reference-satellite" width="8" height="8" patternUnits="userSpaceOnUse"><rect width="8" height="8" fill="#e0e4d9"/><circle cx="3" cy="3" r="1" fill="#879481"/></pattern></defs><rect width="32" height="32" fill="url(#reference-satellite)"/></svg>Stippling: satellite</span>
                <span><svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true"><rect x="1" y="1" width="30" height="30" fill="#d2efed" stroke="#56929c"/></svg>Pale water: a province’s coastal band</span>
            </div>
            {catalog && <div className="reference-legend">{catalog.provinces.filter(province => province.capital).map(province => <span key={province.id}><svg viewBox="-28 -28 56 56" width="28" height="28" aria-hidden="true"><PlayerPiece capital={province.capital} size={44}/></svg>{label(province.id, 'capital')}</span>)}</div>}
            <p>Dark market badges identify capitals. Market numbers and commodity symbols belong to provinces. Stippled provinces are satellites. Capital color and silhouette identify the owner everywhere. Land and its pale coastal band select the same province.</p>
            <p>Named seas are distinct from coastal bands. Inspecting a province emphasizes its connected seas in deep blue with diagonal hatching; this does not guarantee a legal shipment. A shared corner is not a connection. Support arrows point from satellites to markets and can be toggled on the map.</p>
            <p>Eligible targets use blue emphasis; the selected target has a stronger outline. Expansion reachability shades whole land/coastal footprints. Inspect an object for its occupants and actual legal actions. Zoom in to separate dense labels and tokens.</p>
            <p>Use +, −, and Fit, modifier-scroll, trackpad/two-finger pinch, or dragging. Ordinary scrolling moves the page. Keyboard Enter or Space selects a focused province or area. Arrow keys switch workspace tabs.</p>
            {catalog && <div className="reference-legend">{catalog.commodities.map(commodity => <Commodity key={commodity.id} commodity={commodity.id}/>)}</div>}
        </section>
        {!reference && <p role="status">{error ? 'Reference data could not be loaded. Reload to try again.' : 'Loading the public catalog…'}</p>}
        {reference && <>
            <section id="cards"><h2>History cards</h2><p>Circle cards recycle into the next epoch when eligible; square cards leave play. Unplayed-leader counts in the game mean “not publicly played”: held cards, future decks, and private discards remain counted to preserve secrecy.</p>
                <div className="reference-index">{cardGroups.map(group => <a key={group[0].id} href={`#card-${group[0].id}`}>{t(group[0].messageId)}</a>)}</div>
                {cardGroups.map(group => {
                    const card = group[0];
                    const leader = reference.leaders.find(candidate => candidate.id.toLowerCase().replace(/_/g, '-') === card.id);
                    return <article id={`card-${card.id}`} key={card.id}>
                        {group.slice(1).map(copy => <span className="reference-anchor" id={`card-${copy.id}`} key={copy.id}/>)}
                        <h3>{t(card.messageId)}</h3><p className="eyebrow">{t(`kind.${card.kind}`)} · {label(card.shape)}</p>
                        <CardEventText definition={card}/>
                        {card.kind === 'commodity' && <p>Choose one of this card’s commodities. Every player receives the corresponding payout for established markets, adjusted by shortages, surpluses, and Industry. White markers do not pay yet. The commodity’s adjustments are consumed. <a href="#commodities">See all payout boxes.</a></p>}
                        {leader && <><p>Discounts apply to eligible advance purchases this turn. The first leader occupies the reserved box; later leaders are exposed. Patronage may claim eligible exposed leaders by paying their protection money. Printed Word rebates are distinct from discounts and protection money.</p>
                            <ul>{leader.discounts.map(discount => <li key={discount.advance}><a href={`#advance-${discount.advance.toLowerCase()}`}>{advanceName(discount.advance)}</a>: ${discount.amount}{discount.condition && `; $${discount.increasedAmount} with ${label(discount.condition).toLowerCase()}`}</li>)}</ul></>}
                    </article>;
                })}
            </section>
            <section id="advances"><h2>Advances</h2><p>Purchase quotes include credits, prerequisites, and usable leader discounts. Current final prices and cash consequences remain in the game. These are printed reference values.</p>
                <div className="reference-index">{catalog?.advances.map(advance => <a key={advance.id} href={`#advance-${advance.id.toLowerCase()}`}>{advance.letter} · {advanceName(advance.id)}</a>)}</div>
                {catalog?.advances.map(advance => <article id={`advance-${advance.id.toLowerCase()}`} key={advance.id}>
                    <h3>{advance.letter} · {advanceName(advance.id)}</h3><AdvanceBenefits id={advance.id} rules={reference.rules}/>
                    <p>{label(advance.category, 'category')} · Printed cost ${advance.cost} · Credit ${advance.credit} · MR rating {advance.miseryRelief} · Purchase misery {advance.purchaseMisery}</p>
                    <p>Prerequisites: {advance.prerequisites.map(advanceName).join(', ') || 'None'}</p>
                </article>)}
            </section>
            <section id="commodities"><h2>Commodity payout boxes</h2><p>The effective box is established markets plus shortages, minus surpluses, with Industry’s bonus where applicable. Payouts continue the same quadratic formula beyond printed box 10. Current owner payouts and adjustments stay on the game’s Markets tab and cards.</p>
                {catalog?.commodities.map(commodity => <article id={`commodity-${commodity.id.toLowerCase()}`} key={commodity.id}>
                    <h3><Commodity commodity={commodity.id}/></h3><p>Base value ${commodity.baseValue}. White + black dice total {commodity.diceTotal}; the green die selects shortage or surplus. Canceling an adjustment costs its base value.</p>
                    <ol className="payout-boxes" aria-label={`${label(commodity.id, 'commodity')} payout boxes`}>{commodity.printedPayouts.map((amount, index) => <li className="payout-box" key={index}><span>{index + 1}</span><strong>${amount}</strong></li>)}</ol>
                </article>)}
            </section>
            <section id="tracks"><h2>Misery and shipping tracks</h2><h3>Misery</h3>
                <ol className="reference-track">{catalog?.miseryTrack.map(value => <li key={value ?? 'chaos'}>{value ?? t('game.chaos')}</li>)}</ol>
                <p>Misery changes move by spaces, not by subtracting printed box values. Chaos removes a player from ordinary play and produces no numeric final score. A tier is the minimum number of advances owned in any category. Purchased relief is limited by tiers and uses this turn’s misery-relief ratings; Enlightenment reduces its cash cost.</p>
                <h3>Shipping</h3>{[...new Set(catalog?.shippingTrack.map(space => space.fleet))].map(fleet => <div key={fleet}><h4>{label(fleet, 'fleet')}</h4>
                    <ol className="reference-track">{catalog?.shippingTrack.filter(space => space.fleet === fleet).map(space => <li key={space.level}>{space.level}</li>)}</ol></div>)}
                <p>Shipping commitments and available capacity are calculated per destination in the game. A fleet level is not a count of spare ships; access advances and route restrictions still apply.</p>
            </section>
        </>}
        <section id="history"><h2>Activity, commands, and undo</h2>
            <p>Activity records committed actions and outcomes once, newest first. Scroll within its bounded box; collapse it to save space. Cards in activity open their reference here. Player names and emblems reflect their identity when the event occurred and stay unchanged as later actions are appended. An explicit history edit can replace affected entries.</p>
            <p>White, black, and green dice are named explicitly. Market rolls explain their meaning; card draws identify their cause. Cash movements, Printed Word rebates, and protection money are separate quantities. Clearing competition notices leaves the permanent activity intact.</p>
            <p>The Commands expander remains at the bottom of the game. It contains the command input, canonical history, display-name preferences, and authorized history editor. Capital aliases are a display preference for command history; they do not rewrite activity or stored commands.</p>
            <p>Undo defaults to regular turn: your own current-phase actions until another player acts or an outcome is recorded. Competitive scope is stricter; friendly-turn and friendly-game scopes allow broader changes. Setup and complete private batches remain protected. Stale revisions refresh the view and require an explicit retry.</p>
        </section>
    </main>;
}
