import {useContext, useEffect, useState} from 'react';
import {request} from './api';
import type {PublicCatalog} from './api';
import {label} from './labels';
import {t} from './messages';
import {Commodity} from './TablePanels';
import {CommodityReference} from './CommodityReference';
import {AccessLock, LockedAreaPattern, PlayerPiece, SatellitePattern} from './map/MapSymbols';
import {MiseryBox} from './MiseryBox';
import ruleOptions from './ruleOptions.json';
import {CardInspectionDialog} from './CardInspectionDialog';
import {TurnOrderReference} from './TurnOrderReference';
import {ReferenceGameContext} from './HelpLink';
import {ReferenceTrackPlayers} from './ReferenceTrackPlayers';

interface ReferenceData {catalog: PublicCatalog}
let publicReference: Promise<ReferenceData> | undefined;
function loadReference() {
    return publicReference ??= request<ReferenceData>('/api/reference').catch(error => {
        publicReference = undefined;
        throw error;
    });
}

const topics = [
    ['variants', 'reference.variants'],
    ['map-legend', 'reference.map-key'],
    ['commodities', 'reference.payout-boxes'],
    ['turn-order', 'reference.turnOrder'],
    ['income-track', 'reference.income-track'],
    ['misery-track', 'reference.misery-track'],
    ['shipping-track', 'reference.shipping'],
    ['history', 'reference.undo-policies'],
    ['plans', 'plan.helpTitle'],
];

/** Contextual public lookup leaves the live game, current draft and page title intact. */
export default function ReferencePopup({initialTopic, close, catalog: gameCatalog}: {
    initialTopic: string; close: () => void; catalog?: PublicCatalog | null;
}) {
    const current = useContext(ReferenceGameContext);
    const players = current?.players ?? [];
    const [topic, setTopic] = useState(initialTopic);
    const [reference, setReference] = useState<ReferenceData | null>(null);
    const [error, setError] = useState(false);
    const needsCatalog = topic === 'map-legend' || topic === 'commodities' || topic === 'turn-order' ||
        topic.startsWith('commodity-') || topic.endsWith('-track');
    useEffect(() => {
        if (!needsCatalog || gameCatalog) return;
        let active = true;
        setError(false);
        void loadReference().then(data => {if (active) setReference(data);})
            .catch(() => {if (active) setError(true);});
        return () => {active = false;};
    }, [needsCatalog, gameCatalog]);
    const catalog = gameCatalog ?? reference?.catalog;
    return <CardInspectionDialog label={t('reference.help-and-reference')} close={close} className="reference-dialog">
        <div className="reference-popup" data-help-content={topic}>
            <button type="button" className="close-detail" aria-label={t('table.close')} onClick={close}>×</button>
            {topic && <button type="button" className="reference-link" onClick={() => setTopic('')}>{t('reference.help-topics')}</button>}
            {!topic && <><h2>{t('reference.help-and-reference')}</h2>
                <nav aria-label={t('reference.help-topics')}>{topics.map(([id, key]) =>
                    <button type="button" className="secondary" key={id} onClick={() => setTopic(id)}>{t(key)}</button>)}</nav>
            </>}
            {needsCatalog && !catalog && <p role="status">{t(error ? 'reference.unavailable' : 'reference.loading')}</p>}
            {topic === 'unplayed-cards' && <section id="unplayed-cards">
                <h2>{t('reference.unplayedCardsTitle')}</h2>
                <p>{t('reference.unplayedCardsHint')}</p>
                {current?.unplayedCards?.length ? <ul className="unplayed-card-list">
                    {current.unplayedCards.map(card => <li key={card.id} data-unplayed-card={card.id}>{t(card.messageId)}</li>)}
                </ul> : <p>{t('reference.noUnplayedCards')}</p>}
            </section>}
            {(topic === 'variants' || topic.startsWith('variant-')) && <section id="variants"><h2>{t("reference.game-variants")}</h2>{ruleOptions.filter(variant => !topic.startsWith('variant-') || variant.key === topic.slice(8)).map(variant => <article id={`variant-${variant.key}`} key={variant.key}>
            <h3>{t(`option.${variant.key}`)}</h3><p>{t(`reference.variant.${variant.key}`)}</p></article>)}</section>}
            {topic === 'map-legend' && <section id="map-legend"><h2>{t("reference.map-key-and-navigation")}</h2>
            <div className="reference-legend">
                <span><svg viewBox="0 0 32 33" width="28" height="28" aria-hidden="true"><rect width="32" height="33" rx="7" fill="#263c3c"/><text x="16" y="25" textAnchor="middle" fontSize="27" fill="#fffdf5">4</text></svg>{t("reference.dark-badge-capital-market")}</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="barcelona" size={44}/></svg>{t("reference.unnumbered-silhouette-domination-marker")}</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="venice" count={2} size={44}/></svg>{t("reference.numbered-silhouette-token-count")}</span>
                <span><svg viewBox="-28 -28 56 56" width="32" height="32" aria-hidden="true"><PlayerPiece capital="barcelona" fresh size={44}/></svg>{t("reference.white-face-new-this-turn-filled")}</span>
                <span><svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true"><defs><LockedAreaPattern id="reference-locked-area"/></defs><rect width="32" height="32" fill="url(#reference-locked-area)"/><AccessLock x={4} y={4}/></svg>{t("reference.lock-an-advance-is-required")}</span>
                <span><svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true"><defs><SatellitePattern id="reference-satellite"/></defs><rect width="32" height="32" fill="url(#reference-satellite)"/></svg>{t("reference.stippling-satellite")}</span>
                <span><svg viewBox="0 0 32 32" width="28" height="28" aria-hidden="true"><rect x="1" y="1" width="30" height="30" fill="#d2efed" stroke="#56929c"/></svg>{t("reference.pale-water-a-province-s-coastal")}</span>
            </div>
            {catalog && <div className="reference-legend">{catalog.provinces.filter(province => province.capital).map(province => <span key={province.id}><svg viewBox="-28 -28 56 56" width="28" height="28" aria-hidden="true"><PlayerPiece capital={province.capital} size={44}/></svg>{label(province.id, 'capital')}</span>)}</div>}
            <p>{t("reference.capital-color-and-silhouette-identify-the")}</p>
            <p>{t("reference.named-seas-are-distinct-from-coastal")}</p>
            <p>{t("reference.use-and-fit-modifier-scroll-trackpad")}</p>
            <p>{t("reference.activityLayout")}</p>
        </section>}
            {topic === 'commodities' && <section><h2>{t('reference.commodity-payout-boxes')}</h2><nav>{catalog?.commodities.map(commodity => <button type="button" className="secondary" key={commodity.id} onClick={() => setTopic(`commodity-${commodity.id.toLowerCase()}`)}><Commodity commodity={commodity.id}/></button>)}</nav></section>}
            {topic.startsWith('commodity-') && catalog && <CommodityReference catalog={catalog} topic={topic}/>}
            {topic === 'turn-order' && catalog && <TurnOrderReference catalog={catalog}/>}
            {topic === 'income-track' && <section id="income-track"><h2>{t('reference.income-track')}</h2>
                <p>{t('reference.income-markers')}</p>
                <ol className="payout-boxes" aria-label={t('reference.income-track')}>{catalog?.incomeTrack.map((amount, markers) =>
                    <li className={`payout-box${players.some(player => player.boardMarkers === markers) ? ' is-current' : ''}`} key={markers} data-track-box={markers}>
                        <span aria-label={t('reference.income-space', {markers, amount})}>{markers}</span><strong>${amount}</strong>
                        <ReferenceTrackPlayers players={players.filter(player => player.boardMarkers === markers)}/>
                    </li>)}</ol>
                {catalog && <p>{t('reference.income-example', {markers: 3, amount: catalog.incomeTrack[3]})}</p>}
                <p>{t('reference.income-modifiers')}</p>
            </section>}
            {topic === 'misery-track' && <section id="misery-track"><h2>{t('reference.misery-track')}</h2>
            <ol className="reference-track misery-track" aria-label={t('reference.misery-track')}>{catalog?.miseryTrack.map(value => <li key={value ?? 'chaos'} data-track-box={value ?? 'chaos'} className={players.some(player => player.misery === value) ? 'is-current' : ''}><MiseryBox value={value} track={catalog.miseryTrack}/><ReferenceTrackPlayers players={players.filter(player => player.misery === value)}/></li>)}</ol>
            <p>{t('reference.colors-progress-from-green-through-amber')}</p>
        </section>}
            {topic === 'shipping-track' && <section id="shipping-track"><h2>{t('reference.shipping')}</h2>
            <p>{t('reference.shipping-boxes')}</p>
            {[...new Set(catalog?.shippingTrack.map(space => space.fleet))].map(fleet => <div key={fleet}><h3>{label(fleet, 'fleet')}</h3>
                <p>{t(`reference.shipping-${fleet.toLowerCase()}`)}</p>
                <ol className="reference-track">{catalog?.shippingTrack.filter(space => space.fleet === fleet).map(space => <li key={space.level} data-track-fleet={fleet} data-track-box={space.level}
                    className={players.some(player => player.ships.fleet === fleet && player.ships.level === space.level) ? 'is-current' : ''}>
                    {space.level}<ReferenceTrackPlayers players={players.filter(player => player.ships.fleet === fleet && player.ships.level === space.level)}/></li>)}</ol></div>)}
            <p>{t('reference.shipping-commitments-and-available-capacity-are')}</p>
        </section>}
            {(topic === 'history' || topic.startsWith('undo-')) && <section id="history"><h2>{t("reference.undo-policies-55")}</h2>
            <p>{t("reference.choose-a-policy-when-creating-the")}</p>
            {(topic === 'history' || topic === 'undo-competitive') && <article id="undo-competitive"><h3>{t('undoPolicy.competitive')}</h3>
                <p>{t("reference.committed-actions-are-final-unsubmitted-drafts")}</p>
            </article>}
            {(topic === 'history' || topic === 'undo-regular-turn') && <article id="undo-regular-turn"><h3>{t('undoPolicy.regular-turn')}{t("reference.default")}</h3>
                <p>{t("reference.you-may-edit-your-own-actions")}</p>
                <p>{t("reference.for-example-you-may-correct-an")}</p>
            </article>}
            {(topic === 'history' || topic === 'undo-friendly-turn') && <article id="undo-friendly-turn"><h3>{t('undoPolicy.friendly-turn')}</h3>
                <p>{t("reference.the-current-activity-s-owner-may")}</p>
            </article>}
            {topic === 'undo-friendly-game' && <article id="undo-friendly-game"><h3>{t('undoPolicy.friendly-game')}</h3>
                <p>{t("reference.everyone-has-friendly-turn-permissions-the")}</p>
            </article>}
            {(topic === 'history' || topic === 'undo-linear-game') && <article id="undo-linear-game"><h3>{t('undoPolicy.linear-game')}</h3>
                <p>{t('history.linearDescription')}</p><p>{t('history.linearLimits')}</p><p>{t('history.rewindConsequences')}</p><p>{t('history.randomnessRemoved')}</p><p>{t('history.linearPrivate')}</p>
            </article>}
            {topic !== 'undo-linear-game' && <><h3>{t("reference.limits-shared-by-all-editable-policies")}</h3>
            <ul>
                <li>{t("reference.setup-is-protected-committed-simultaneous-decisions")}</li>
                <li>{t("reference.dice-draws-shuffles-and-random-trades")}</li>
                <li>{t("reference.you-can-retain-another-player-s")}</li>
                <li>{t("reference.every-later-action-must-still-be")}</li>
            </ul>
            <p>{t("reference.undo-last-action-appears-in-the")}<code>@entry</code>{t("reference.references-retain-recorded-actions-accepted-edits")}</p></>}
        </section>}
            {topic === 'plans' && <section id="plans"><h2>{t('plan.helpTitle')}</h2>
                <p>{t('plan.helpSaving')}</p><p>{t('plan.helpPreview')}</p><p>{t('plan.helpAutomatic')}</p><p>{t('plan.helpBoundaries')}</p>
            </section>}
        </div>
    </CardInspectionDialog>;
}
