import {useEffect, useState} from 'react';
import {request} from './api';
import type {CardDefinition, PublicCatalog, Snapshot} from './api';
import {advanceName, label} from './labels';
import {t} from './messages';
import {CardEventText} from './CardEventText';
import {AdvanceBenefits} from './AdvanceBenefits';
import {Commodity} from './TablePanels';
import {AccessLock, PlayerPiece} from './map/MapSymbols';
import {MiseryBox} from './MiseryBox';
import {CardRecurrence} from './CardRecurrence';

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
        <header><a href="/">AoR</a><h1>Help and reference</h1><p>Variants, symbols, printed values, and undo policies.</p></header>
        <nav aria-label="Help topics"><a href="#variants">Variants</a>
            <a href="#map-legend">Map key</a><a href="#cards">Cards</a><a href="#advances">Advances</a>
            <a href="#commodities">Payout boxes</a><a href="#tracks">Tracks</a><a href="#history">Undo policies</a></nav>
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
            <p>Capital color and silhouette identify the owner everywhere. Land and its pale coastal band select the same province.</p>
            <p>Named seas are distinct from coastal bands. Inspecting a province emphasizes its connected seas in deep blue with diagonal hatching; this does not guarantee a legal shipment. A shared corner is not a connection. Support arrows are always visible and point from satellites to markets.</p>
            <p>Eligible targets use blue emphasis; the selected target has a stronger outline. Expansion reachability shades whole land/coastal footprints. Inspect an object for its occupants and actual legal actions. Zoom in to separate dense labels and tokens.</p>
            <p>Use +, −, and Fit, modifier-scroll, trackpad/two-finger pinch, or dragging. Ordinary scrolling moves the page. Keyboard Enter or Space selects a focused province or area. Arrow keys switch workspace tabs.</p>
        </section>
        {!reference && <p role="status">{error ? 'Reference data could not be loaded. Reload to try again.' : 'Loading the public catalog…'}</p>}
        {reference && <>
            <section id="cards"><h2>History cards</h2><p>Recurring cards can return in a later epoch if already discarded. Other cards leave the deck after use.</p>
                <article id="commodity-cards"><h3>Commodity cards</h3>
                    <p>Pay every player for their established markets. Choose one commodity on a dual card; white markers do not pay. The chosen commodity’s shortage/surplus adjustments are consumed. <a href="#commodities">Payout rules and boxes</a>.</p>
                </article>
                <article id="leader-cards"><h3>Leaders</h3>
                    <p>Discounts apply to eligible advance purchases this turn. The first leader occupies the reserved box; later leaders are exposed. Patronage may claim eligible exposed leaders by paying their protection money. Printed Word rebates are distinct from discounts and protection money.</p>
                    <p>Unplayed-leader counts mean “not publicly played”: held cards, future decks, and private discards remain counted to preserve secrecy.</p>
                </article>
                <div className="reference-index">{cardGroups.map(group => <a key={group[0].id} href={`#card-${group[0].id}`}>{t(group[0].messageId)}</a>)}</div>
                {cardGroups.map(group => {
                    const card = group[0];
                    const leader = reference.leaders.find(candidate => candidate.id.toLowerCase().replace(/_/g, '-') === card.id);
                    return <article id={`card-${card.id}`} key={card.id}>
                        {group.slice(1).map(copy => <span className="reference-anchor" id={`card-${copy.id}`} key={copy.id}/>)}
                        <h3>{t(card.messageId)}</h3><CardRecurrence definition={card}/>
                        <CardEventText definition={card}/>
                        {card.kind === 'commodity' && <p><a href="#commodity-cards">Commodity card rules</a> · <a href="#commodities">Payout boxes</a></p>}
                        {leader && <><p><a href="#leader-cards">Leader rules</a></p>
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
            <section id="commodities"><h2>Commodity payout boxes</h2><p>The effective box is established markets plus shortages, minus surpluses, with Industry’s bonus where applicable. Payouts continue the same quadratic formula beyond printed box 10. Live values on the game’s Markets tab and cards follow current marker ownership, including white markers during Expansion. Actual card payments require colored markers.</p>
                <p>White + black dice select the matching total below; the green die selects shortage or surplus. Canceling an adjustment costs the commodity’s base value.</p>
                {catalog?.commodities.map(commodity => <article id={`commodity-${commodity.id.toLowerCase()}`} key={commodity.id}>
                    <h3><Commodity commodity={commodity.id}/></h3><p>Base value ${commodity.baseValue} · Dice total {commodity.diceTotal}</p>
                    <ol className="payout-boxes" aria-label={`${label(commodity.id, 'commodity')} payout boxes`}>{commodity.printedPayouts.map((amount, index) => <li className="payout-box" key={index}><span>{index + 1}</span><strong>${amount}</strong></li>)}</ol>
                </article>)}
            </section>
            <section id="tracks"><h2>Misery and shipping tracks</h2><h3 id="misery-track">Misery</h3>
                <ol className="reference-track misery-track" aria-label="Misery track">{catalog?.miseryTrack.map(value => <li key={value ?? 'chaos'}><MiseryBox value={value} track={catalog.miseryTrack}/></li>)}</ol>
                <p>Colors progress from green through amber to red as track steps become more severe; Chaos is the darkest box. Misery changes move by spaces, not by subtracting printed box values. Chaos removes a player from ordinary play and produces no numeric final score.</p>
                <p>A tier is the minimum number of advances owned in any category. Purchased relief is limited by tiers and uses this turn’s misery-relief ratings; Enlightenment reduces its cash cost.</p>
                <h3 id="shipping-track">Shipping</h3>{[...new Set(catalog?.shippingTrack.map(space => space.fleet))].map(fleet => <div key={fleet}><h4>{label(fleet, 'fleet')}</h4>
                    <ol className="reference-track">{catalog?.shippingTrack.filter(space => space.fleet === fleet).map(space => <li key={space.level}>{space.level}</li>)}</ol></div>)}
                <p>Shipping commitments and available capacity are calculated per destination in the game. A fleet level is not a count of spare ships; access advances and route restrictions still apply.</p>
            </section>
        </>}
        <section id="history"><h2>Undo policies</h2>
            <p>Choose a policy when creating the game; it is fixed once setup starts. Here, “turn” means your activity within one phase, including interruptions such as Cathedral. It does not extend across phases.</p>
            <article id="undo-competitive"><h3>{t('undoPolicy.competitive')}</h3>
                <p>Committed actions are final. Unsubmitted drafts remain editable, and you may replace your own unrevealed private submission until its batch commits.</p>
            </article>
            <article id="undo-regular-turn"><h3>{t('undoPolicy.regular-turn')} (default)</h3>
                <p>You may edit your own actions in your current phase activity. The window starts after the most recent activity boundary, another player’s action, or recorded random outcome. It closes when your activity ends.</p>
                <p>For example, you may correct an unopposed Expansion placement before passing, provided it caused no draw or other recorded outcome.</p>
            </article>
            <article id="undo-friendly-turn"><h3>{t('undoPolicy.friendly-turn')}</h3>
                <p>The current activity’s owner may edit back to its start, including across other players’ interruption answers and recorded outcomes. The shared restrictions below still apply. The window closes when the activity ends.</p>
            </article>
            <article id="undo-friendly-game"><h3>{t('undoPolicy.friendly-game')}</h3>
                <p>Everyone has Friendly turn permissions. The game’s creator may additionally edit any post-setup history, including earlier phases and completed games. This extra permission belongs to the creator, not every player.</p>
            </article>
            <h3>Limits shared by all editable policies</h3>
            <ul>
                <li>Setup is protected. Committed simultaneous decisions must remain complete batches; an edit cannot cut through one.</li>
                <li>Dice, draws, shuffles, and random trades must remain the same outcomes of the same events. No policy allows rerolls or an edit that adds or removes a required random event. A rolled attack cannot be deleted to try again.</li>
                <li>You can retain another player’s original answer by reference or remove it when the remaining history is valid. You cannot write a different answer for them or reveal their private information.</li>
                <li>Every later action must still be legal. An invalid edit is rejected in full; existing play stays intact.</li>
            </ul>
            <p>Undo last action appears in the decision panel when permitted. Open Commands → Edit history for the suffix editor; its <code>@entry</code> references retain recorded actions. Accepted edits keep an audit trail and clear obsolete drafts; stale edits need a fresh review and retry. Activity keeps its entry-time identities unless an explicit edit replaces the affected entries.</p>
        </section>
    </main>;
}
