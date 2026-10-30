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
import ruleOptions from './ruleOptions.json';
import {CardRecurrence} from './CardRecurrence';

interface ReferenceData {
    catalog: PublicCatalog;
    cards: CardDefinition[];
    leaders: {id: string; discounts: {advance: string; amount: number; condition: string | null; increasedAmount: number | null}[]}[];
    rules: Snapshot['game']['rules'];
}

/** Static public rules and catalog reference; it never loads a player's hand or game history. */
export default function ReferencePage() {
    const [reference, setReference] = useState<ReferenceData | null>(null);
    const [error, setError] = useState(false);
    useEffect(() => {
        let active = true;
        document.title = t('reference.pageTitle');
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
        <header><a href="/">{t("reference.aor")}</a><h1>{t("reference.help-and-reference")}</h1><p>{t("reference.variants-symbols-printed-values-and-undo")}</p></header>
        <nav aria-label={t("reference.help-topics")}><a href="#variants">{t("reference.variants")}</a>
            <a href="#map-legend">{t("reference.map-key")}</a><a href="#cards">{t("reference.cards")}</a><a href="#advances">{t("reference.advances")}</a>
            <a href="#commodities">{t("reference.payout-boxes")}</a><a href="#tracks">{t("reference.tracks")}</a><a href="#history">{t("reference.undo-policies")}</a></nav>
        <section id="variants"><h2>{t("reference.game-variants")}</h2>{ruleOptions.map(variant => <article id={`variant-${variant.key}`} key={variant.key}>
            <h3>{t(`option.${variant.key}`)}</h3><p>{t(`reference.variant.${variant.key}`)}</p></article>)}</section>
        <section id="map-legend"><h2>{t("reference.map-key-and-navigation")}</h2>
            <div className="reference-legend">
                <span><svg viewBox="0 0 32 33" width="28" height="28" aria-hidden="true"><rect width="32" height="33" rx="7" fill="#263c3c"/><text x="16" y="25" textAnchor="middle" fontSize="27" fill="#fffdf5">4</text></svg>{t("reference.dark-badge-capital-market")}</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="barcelona" size={44}/></svg>{t("reference.unnumbered-silhouette-domination-marker")}</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="venice" count={2} size={44}/></svg>{t("reference.numbered-silhouette-token-count")}</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="barcelona" fresh size={44}/></svg>{t("reference.white-face-new-this-turn-filled")}</span>
                <span><svg viewBox="0 0 24 24" width="28" height="28" aria-hidden="true"><AccessLock/></svg>{t("reference.lock-an-advance-is-required")}</span>
                <span><svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true"><defs><pattern id="reference-satellite" width="8" height="8" patternUnits="userSpaceOnUse"><rect width="8" height="8" fill="#e0e4d9"/><circle cx="3" cy="3" r="1" fill="#879481"/></pattern></defs><rect width="32" height="32" fill="url(#reference-satellite)"/></svg>{t("reference.stippling-satellite")}</span>
                <span><svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true"><rect x="1" y="1" width="30" height="30" fill="#d2efed" stroke="#56929c"/></svg>{t("reference.pale-water-a-province-s-coastal")}</span>
            </div>
            {catalog && <div className="reference-legend">{catalog.provinces.filter(province => province.capital).map(province => <span key={province.id}><svg viewBox="-28 -28 56 56" width="28" height="28" aria-hidden="true"><PlayerPiece capital={province.capital} size={44}/></svg>{label(province.id, 'capital')}</span>)}</div>}
            <p>{t("reference.capital-color-and-silhouette-identify-the")}</p>
            <p>{t("reference.named-seas-are-distinct-from-coastal")}</p>
            <p>{t("reference.eligible-targets-use-blue-emphasis-the")}</p>
            <p>{t("reference.use-and-fit-modifier-scroll-trackpad")}</p>
        </section>
        {!reference && <p role="status">{t(error ? 'reference.unavailable' : 'reference.loading')}</p>}
        {reference && <>
            <section id="cards"><h2>{t("reference.history-cards")}</h2><p>{t("reference.recurring-cards-can-return-in-a")}</p>
                <article id="commodity-cards"><h3>{t("reference.commodity-cards")}</h3>
                    <p>{t("reference.pay-every-player-for-their-established")}<a href="#commodities">{t("reference.payout-rules-and-boxes")}</a>.</p>
                </article>
                <article id="leader-cards"><h3>{t("reference.leaders")}</h3>
                    <p>{t("reference.discounts-apply-to-eligible-advance-purchases")}</p>
                    <p>{t("reference.unplayed-leader-counts-mean-not-publicly")}</p>
                </article>
                <div className="reference-index">{cardGroups.map(group => <a key={group[0].id} href={`#card-${group[0].id}`}>{t(group[0].messageId)}</a>)}</div>
                {cardGroups.map(group => {
                    const card = group[0];
                    const leader = reference.leaders.find(candidate => candidate.id.toLowerCase().replace(/_/g, '-') === card.id);
                    return <article id={`card-${card.id}`} key={card.id}>
                        {group.slice(1).map(copy => <span className="reference-anchor" id={`card-${copy.id}`} key={copy.id}/>)}
                        <h3>{t(card.messageId)}</h3><CardRecurrence definition={card}/>
                        <CardEventText definition={card}/>
                        {card.kind === 'commodity' && <p><a href="#commodity-cards">{t("reference.commodity-card-rules")}</a> · <a href="#commodities">{t("reference.payout-boxes-34")}</a></p>}
                        {leader && <><p><a href="#leader-cards">{t("reference.leader-rules")}</a></p>
                            <ul>{leader.discounts.map(discount => <li key={discount.advance}><a href={`#advance-${discount.advance.toLowerCase()}`}>{advanceName(discount.advance)}</a>: ${discount.amount}{discount.condition && t('reference.increasedDiscount', {amount: discount.increasedAmount ?? 0, condition: label(discount.condition).toLowerCase()})}</li>)}</ul></>}
                    </article>;
                })}
            </section>
            <section id="advances"><h2>{t("reference.advances-36")}</h2><p>{t("reference.purchase-quotes-include-credits-prerequisites-and")}</p>
                <div className="reference-index">{catalog?.advances.map(advance => <a key={advance.id} href={`#advance-${advance.id.toLowerCase()}`}>{advance.letter} · {advanceName(advance.id)}</a>)}</div>
                {catalog?.advances.map(advance => <article id={`advance-${advance.id.toLowerCase()}`} key={advance.id}>
                    <h3>{advance.letter} · {advanceName(advance.id)}</h3><AdvanceBenefits id={advance.id} rules={reference.rules}/>
                    <p>{label(advance.category, 'category')}{t("reference.printed-cost")}{advance.cost}{t("reference.credit")}{advance.credit}{t("reference.mr-rating")}{advance.miseryRelief}{t("reference.purchase-misery")}{advance.purchaseMisery}</p>
                    <p>{t("reference.prerequisites")}{advance.prerequisites.map(advanceName).join(', ') || t('reference.none')}</p>
                </article>)}
            </section>
            <section id="commodities"><h2>{t("reference.commodity-payout-boxes")}</h2><p>{t("reference.the-effective-box-is-established-markets")}</p>
                <p>{t("reference.white-black-dice-select-the-matching")}</p>
                {catalog?.commodities.map(commodity => <article id={`commodity-${commodity.id.toLowerCase()}`} key={commodity.id}>
                    <h3><Commodity commodity={commodity.id}/></h3><p>{t("reference.base-value")}{commodity.baseValue}{t("reference.dice-total")}{commodity.diceTotal}</p>
                    <ol className="payout-boxes" aria-label={t('reference.payoutBoxes', {commodity: label(commodity.id, 'commodity')})}>{commodity.printedPayouts.map((amount, index) => <li className="payout-box" key={index}><span>{index + 1}</span><strong>${amount}</strong></li>)}</ol>
                </article>)}
            </section>
            <section id="tracks"><h2>{t("reference.misery-and-shipping-tracks")}</h2><h3 id="misery-track">{t("reference.misery")}</h3>
                <ol className="reference-track misery-track" aria-label={t("reference.misery-track")}>{catalog?.miseryTrack.map(value => <li key={value ?? 'chaos'}><MiseryBox value={value} track={catalog.miseryTrack}/></li>)}</ol>
                <p>{t("reference.colors-progress-from-green-through-amber")}</p>
                <p>{t("reference.a-tier-is-the-minimum-number")}</p>
                <h3 id="shipping-track">{t("reference.shipping")}</h3>{[...new Set(catalog?.shippingTrack.map(space => space.fleet))].map(fleet => <div key={fleet}><h4>{label(fleet, 'fleet')}</h4>
                    <ol className="reference-track">{catalog?.shippingTrack.filter(space => space.fleet === fleet).map(space => <li key={space.level}>{space.level}</li>)}</ol></div>)}
                <p>{t("reference.shipping-commitments-and-available-capacity-are")}</p>
            </section>
        </>}
        <section id="history"><h2>{t("reference.undo-policies-55")}</h2>
            <p>{t("reference.choose-a-policy-when-creating-the")}</p>
            <article id="undo-competitive"><h3>{t('undoPolicy.competitive')}</h3>
                <p>{t("reference.committed-actions-are-final-unsubmitted-drafts")}</p>
            </article>
            <article id="undo-regular-turn"><h3>{t('undoPolicy.regular-turn')}{t("reference.default")}</h3>
                <p>{t("reference.you-may-edit-your-own-actions")}</p>
                <p>{t("reference.for-example-you-may-correct-an")}</p>
            </article>
            <article id="undo-friendly-turn"><h3>{t('undoPolicy.friendly-turn')}</h3>
                <p>{t("reference.the-current-activity-s-owner-may")}</p>
            </article>
            <article id="undo-friendly-game"><h3>{t('undoPolicy.friendly-game')}</h3>
                <p>{t("reference.everyone-has-friendly-turn-permissions-the")}</p>
            </article>
            <h3>{t("reference.limits-shared-by-all-editable-policies")}</h3>
            <ul>
                <li>{t("reference.setup-is-protected-committed-simultaneous-decisions")}</li>
                <li>{t("reference.dice-draws-shuffles-and-random-trades")}</li>
                <li>{t("reference.you-can-retain-another-player-s")}</li>
                <li>{t("reference.every-later-action-must-still-be")}</li>
            </ul>
            <p>{t("reference.undo-last-action-appears-in-the")}<code>@entry</code>{t("reference.references-retain-recorded-actions-accepted-edits")}</p>
        </section>
    </main>;
}
