import type {CommodityPayoutView, PlayerView, PublicCatalog, Snapshot} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';
import {TableIcon} from './TableIcon';
import {CommodityIcon, PlayerPiece} from './map/MapSymbols';
import {useMapSelection} from './map/MapSelection';

export function Emblem({player, size = 20}: {player: PlayerView; size?: number}) {
    return <svg width={size} height={size} viewBox="-28 -28 56 56" aria-hidden="true">
        <PlayerPiece capital={player.capital} size={44}/>
    </svg>;
}

export function Commodity({commodity}: {commodity: string}) {
    return <span className="commodity-name"><svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true">
        <CommodityIcon commodity={commodity}/></svg>{label(commodity, 'commodity')}</span>;
}

function PlayerDetails({player, catalog}: {player: PlayerView; catalog: PublicCatalog | null}) {
    return <div className="player-detail"><h3>{player.name}</h3>
        <dl className="detail-list">
            <div><dt>{t('table.income')}</dt><dd>{player.income ? `$${player.income.ordinary}` : t('table.unavailable')}</dd></div>
            {player.income && <>
                <div><dt>{t('table.baseIncome')}</dt><dd>${player.income.baseIncome}</dd></div>
                <div><dt>{advanceName('middle-class')}</dt><dd>${player.income.middleClassBonus}</dd></div>
                <div><dt>{t('table.conditionalProfit')}</dt><dd>${player.income.interestProfit}</dd></div>
                <div><dt>{t('controls.spaces')} · {t('action.relieveMisery')}</dt><dd>{player.income.reliefSpaces}</dd></div>
            </>}
            <div><dt>{t('game.pool')}</dt><dd>{player.poolTokens}</dd></div>
            <div><dt>{t('game.tokens')}</dt><dd>{player.stockTokens}</dd></div>
            <div><dt>{t('game.stockMarkers')}</dt><dd>{player.stockMarkers}</dd></div>
            <div><dt>{t('game.boardMarkers')}</dt><dd>{player.boardMarkers}</dd></div>
        </dl>
        <h4>{t('game.advances')}</h4>
        <ul className="plain-list">{player.advances.map(advance => <li key={advance}>{advanceName(advance)}</li>)}</ul>
        {catalog && <details className="track-reference"><summary>{t('table.referenceTracks')}</summary>
            <h4>{t('game.misery')}</h4>
            <ol className="reference-track">{catalog.miseryTrack.map(value => <li key={value ?? 'chaos'}
                aria-current={value === player.misery ? 'step' : undefined}>{value ?? t('game.chaos')}</li>)}</ol>
            <h4>{t('game.ships')}</h4>
            {[...new Set(catalog.shippingTrack.map(space => space.fleet))].map(fleet => <div key={fleet} className="fleet-reference">
                <span>{label(fleet, 'fleet')}</span>
                <ol className="reference-track">{catalog.shippingTrack.filter(space => space.fleet === fleet).map(space => <li key={space.level}
                    aria-current={fleet === player.ships.fleet && space.level === player.ships.level ? 'step' : undefined}>{space.level}</li>)}</ol>
            </div>)}
        </details>}
    </div>;
}

export function PlayerOverview({snapshot}: {snapshot: Snapshot}) {
    const order = snapshot.game.turnOrder.length ? snapshot.game.turnOrder : snapshot.game.capitalOrder;
    const players = [...snapshot.game.players].sort((first, second) => {
        const a = order.indexOf(first.id), b = order.indexOf(second.id);
        return (a < 0 ? Infinity : a) - (b < 0 ? Infinity : b);
    });
    return <section className="player-overview" aria-label={t('game.table')}>
        {players.map(player => {
            const active = snapshot.prompt?.players.includes(player.id);
            const sealed = snapshot.submitted.includes(player.id);
            const finished = snapshot.game.stage === 'FINISHED';
            const status = finished ? t('game.finished') : sealed ? t('game.submitted') : active ? t('table.decision') : t('game.waiting');
            const fleet = player.ships.fleet === 'NONE' ? '—' : `${label(player.ships.fleet, 'fleet')} ${player.ships.level}`;
            return <details key={player.id} className={`player-summary ${player.id === snapshot.viewerId ? 'is-viewer' : ''} ${active ? 'is-active' : ''}`}>
                <summary aria-label={`${player.name}, ${status}, ${t('game.cash')} $${player.cash}, ${t('table.income')} ${player.income ? `$${player.income.ordinary}` : t('table.unavailable')}, ${t('game.misery')} ${player.misery ?? t('game.chaos')}, ${t('game.ships')} ${fleet}, ${player.handCount} ${t('game.cards')}`}>
                    <span className="player-identity"><Emblem player={player}/><strong>{player.capital ? provinceName(player.capital) : player.name}</strong>
                        <small>{player.id === snapshot.viewerId ? t('table.you') : order.includes(player.id) ? String(order.indexOf(player.id) + 1).padStart(2, '0') : ''}</small></span>
                    <span className="player-money"><span title={t('game.cash')} aria-label={`${t('game.cash')}: $${player.cash}`}><TableIcon kind="cash"/> <b>${player.cash}</b></span>
                        <span className="income-value" title={t('table.income')} aria-label={`${t('table.income')}: ${player.income ? `$${player.income.ordinary}` : t('table.unavailable')}`}><TableIcon kind="income"/> <b>{player.income ? `$${player.income.ordinary}` : '—'}</b></span></span>
                    <span className="player-stats"><span title={t('game.misery')}><TableIcon kind="misery"/> {player.misery ?? t('game.chaos')}</span>
                        <span title={t('game.ships')}><TableIcon kind="ship"/> {fleet}</span><span title={t('game.cards')}><TableIcon kind="cards"/> {player.handCount}</span></span>
                    {!finished && <span className="player-status">{status}</span>}
                </summary><PlayerDetails player={player} catalog={snapshot.game.catalog}/>
            </details>;
        })}
    </section>;
}

export function Payouts({snapshot, payout}: {snapshot: Snapshot; payout: CommodityPayoutView}) {
    return <span className="payouts">{payout.owners.map(owner => {
        const player = snapshot.game.players.find(candidate => candidate.id === owner.player);
        return <span key={owner.player} className="owner-payout" title={`${playerName(snapshot, owner.player)}: $${owner.payout}`}>
            {player && <Emblem player={player} size={15}/>}<span className="sr-only">{playerName(snapshot, owner.player)} </span><b>${owner.payout}</b>
        </span>;
    })}</span>;
}

export function Markets({snapshot}: {snapshot: Snapshot}) {
    return <section className="markets-workspace" aria-label={t('table.markets')}>
        <div className="market-grid">{snapshot.game.commodityPayouts.map(payout => <details className="market-tile" key={payout.commodity}>
            <summary><Commodity commodity={payout.commodity}/>
                <span className="adjustment-badges">{(['SHORTAGE', 'SURPLUS'] as const).map(adjustment => {
                    const count = payout.adjustments.filter(marker => marker === adjustment).length;
                    return count > 0 && <span key={adjustment} title={label(adjustment, 'adjustment')}>{adjustment === 'SHORTAGE' ? '↑' : '↓'}{count}</span>;
                })}</span><Payouts snapshot={snapshot} payout={payout}/></summary>
            <div className="market-detail">{payout.owners.map(owner => <div key={owner.player}>
                <strong>{playerName(snapshot, owner.player)} · ${owner.payout}</strong>
                <p>{t('table.markets')}: {owner.marketCount} · {t('table.payoutBox')}: {owner.effectiveBox}</p>
                <p>{owner.provinces.map(provinceName).join(', ')}</p>
                {owner.industryBonus > 0 && <p>{advanceName('industry')}: +{owner.industryBonus} {t('table.payoutBox')}</p>}
            </div>)}{payout.owners.length === 0 && <p>{t('table.noOwners')}</p>}</div>
        </details>)}</div>
    </section>;
}

export function Hand({snapshot}: {snapshot: Snapshot}) {
    const {selectedCard, setSelectedCard, setSelectedAdvance, setPicker} = useMapSelection();
    return <section className="table-hand" aria-label={t('game.hand')}>
        <div className="hand-heading"><p className="eyebrow">{t('game.hand')}</p><strong>{snapshot.game.hand.length} {t('game.cards').toLowerCase()}</strong></div>
        <div className="hand-cards">{snapshot.game.hand.map(card => {
            const offer = snapshot.game.cardOffers.find(candidate => candidate.reference === card.reference);
            return <button type="button" key={card.reference} data-hand-card={card.reference}
                aria-pressed={selectedCard === card.reference} className={`hand-card ${card.definition.kind}`}
                onClick={() => { setSelectedCard(card.reference); setSelectedAdvance(''); setPicker(null); }}>
                <span className="eyebrow">{t(`kind.${card.definition.kind}`)}</span><h3>{t(card.definition.messageId)}</h3>
                {snapshot.game.burdenReferences.includes(card.reference) && <span className="burden-badge">{t('game.burden')}</span>}
                {offer?.commodities.map(commodity => {
                    const payout = snapshot.game.commodityPayouts.find(candidate => candidate.commodity === commodity);
                    return <span className="card-commodity" key={commodity}><Commodity commodity={commodity}/>{payout && <Payouts snapshot={snapshot} payout={payout}/>}</span>;
                })}
                {offer?.leaderDiscounts.map(discount => <span className="leader-discount" key={discount.advance}>
                    <span>{advanceName(discount.advance)}</span><strong>−${discount.effectiveDiscount}</strong>
                </span>)}
            </button>;
        })}{snapshot.game.hand.length === 0 && <p className="muted">{t('game.noCards')}</p>}</div>
    </section>;
}

export function AdvanceTiles({snapshot}: {snapshot: Snapshot}) {
    const {selectedAdvance, setSelectedAdvance, setSelectedCard, setPicker} = useMapSelection();
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const advances = snapshot.game.catalog?.advances ?? [];
    const categories = [...new Set(advances.map(advance => advance.category))];
    return <section className="advances-workspace" aria-label={t('game.advances')}>
        {categories.map(category => <div className="advance-category" key={category}>
            <h3>{label(category, 'category')} <small>{advances.filter(advance => advance.category === category && me?.advances.includes(advance.id)).length}</small></h3>
            <div className="advance-tiles">{advances.filter(advance => advance.category === category).map(advance => {
                const offer = snapshot.game.advanceOffers.find(candidate => candidate.advance === advance.id);
                const owned = me?.advances.includes(advance.id);
                return <button type="button" className={`advance-tile ${owned ? 'owned' : ''}`} key={advance.id}
                    aria-pressed={selectedAdvance === advance.id} onClick={() => { setSelectedAdvance(advance.id); setSelectedCard(''); setPicker(null); }}>
                    <span className="advance-letter">{advance.letter}</span><strong>{advanceName(advance.id)}</strong>
                    <span>{owned ? t('table.owned') : offer?.price !== null && offer?.price !== undefined ? `$${offer.price}` : '—'}</span>
                    {offer?.unavailableReason && <small>{t(`error.${offer.unavailableReason}`)}</small>}
                </button>;
            })}</div>
        </div>)}
    </section>;
}
