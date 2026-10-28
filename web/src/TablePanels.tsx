import type {CommodityPayoutView, PlayerView, PublicCatalog, Snapshot} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';
import {TableIcon} from './TableIcon';
import {CardEventText} from './CardEventText';
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
            {player.poolAvailable && <div className="player-pool-detail"><dt>{t('game.pool')}</dt><dd>{player.poolTokens}</dd></div>}
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
    const {playerPicker} = useMapSelection();
    const order = snapshot.game.turnOrder.length ? snapshot.game.turnOrder : snapshot.game.capitalOrder;
    const players = [...snapshot.game.players].sort((first, second) => {
        const a = order.indexOf(first.id), b = order.indexOf(second.id);
        return (a < 0 ? Infinity : a) - (b < 0 ? Infinity : b);
    });
    return <section className="player-overview" aria-label={t('game.table')}>
        {players.map(player => {
            const active = snapshot.prompt?.players.includes(player.id);
            const submitted = snapshot.submitted.includes(player.id);
            const finished = snapshot.game.stage === 'FINISHED';
            const status = finished ? t('game.finished') : submitted ? t('game.submitted') : active ? t('table.decision') : t('game.waiting');
            const fleet = player.ships.fleet === 'NONE' ? '—' : `${label(player.ships.fleet, 'fleet')} ${player.ships.level}`;
            const eligible = playerPicker?.eligible.includes(player.id);
            const picked = playerPicker?.selected.includes(player.id);
            const whiteMarkers = snapshot.game.board.filter(province => province.marker?.owner === player.id && province.marker.side === 'WHITE').length;
            return <details data-player={player.id} key={player.id} className={`player-summary ${player.id === snapshot.viewerId ? 'is-viewer' : ''} ${active ? 'is-active' : ''} ${eligible ? 'eligible-target' : ''} ${picked ? 'picked-target' : ''}`}>
                <summary aria-pressed={eligible ? picked : undefined} onClick={event => {
                    if (eligible) {event.preventDefault(); playerPicker?.pick(player.id);}
                }} aria-label={`${playerName(snapshot, player.id)}, ${player.name}, ${status}, ${t('game.cash')} $${player.cash}, ${t('table.income')} ${player.income ? `$${player.income.ordinary}` : t('table.unavailable')}, ${t('game.misery')} ${player.misery ?? t('game.chaos')}, ${t('game.ships')} ${fleet}, ${player.handCount} ${t('game.cards')}`}>
                    <span className="player-identity"><Emblem player={player}/><strong>{player.capital ? provinceName(player.capital) : player.name}</strong>
                        <small>{player.id === snapshot.viewerId ? t('table.you') : order.includes(player.id) ? String(order.indexOf(player.id) + 1).padStart(2, '0') : ''}</small></span>
                    {player.capital && <span className="player-person">{player.name}</span>}
                    <span className="player-money"><span title={t('game.cash')} aria-label={`${t('game.cash')}: $${player.cash}`}><TableIcon kind="cash"/> <b>${player.cash}</b></span>
                        <span className="income-value" title={t('table.income')} aria-label={`${t('table.income')}: ${player.income ? `$${player.income.ordinary}` : t('table.unavailable')}`}><TableIcon kind="income"/> <b>{player.income ? `$${player.income.ordinary}` : '—'}</b></span></span>
                    <span className="player-stats"><span title={t('game.misery')}><TableIcon kind="misery"/> {player.misery ?? t('game.chaos')}</span>
                        <span title={t('game.ships')}><TableIcon kind="ship"/> {fleet}</span><span title={t('game.cards')}><TableIcon kind="cards"/> {player.handCount}</span></span>
                    {player.poolAvailable && <span className="player-pool" title={t('table.poolTokens')} aria-label={`${t('table.poolTokens')}: ${player.poolTokens}`}><TableIcon kind="token"/><b>{player.poolTokens}</b><small>{t('table.poolTokens')}</small></span>}
                    {snapshot.game.stage === 'EXPANSION' && <span className="player-white-markers" aria-label={`${t('table.whiteMarkers')}: ${whiteMarkers}`}>
                        <b>{whiteMarkers}</b><small>{t('table.whiteMarkers')}</small>
                    </span>}
                    {eligible && <span className="target-status">{t(picked ? 'table.selectedTarget' : 'table.legalTarget')}</span>}
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

export function CommodityAdjustments({payout}: {payout: CommodityPayoutView}) {
    return <span className="adjustment-badges">{(['SHORTAGE', 'SURPLUS'] as const).map(adjustment => {
        const count = payout.adjustments.filter(marker => marker === adjustment).length;
        return count > 0 && <span key={adjustment} className={`adjustment-badge ${adjustment}`}>
            {adjustment === 'SHORTAGE' ? '↑' : '↓'} {label(adjustment, 'adjustment')} ×{count}
        </span>;
    })}</span>;
}

export function PayoutReference({snapshot, payout}: {snapshot: Snapshot; payout: CommodityPayoutView}) {
    const definition = snapshot.game.catalog?.commodities.find(commodity => commodity.id === payout.commodity);
    if (!definition) return null;
    return <details className="payout-reference" data-commodity={payout.commodity}><summary>{t('table.fullPayouts')}</summary>
        <ol className="payout-boxes" aria-label={t('table.payoutBoxes')}>
            {definition.printedPayouts.map((amount, index) => {
                const owners = payout.owners.filter(owner => owner.effectiveBox === index + 1);
                return <li key={index} className={`payout-box ${owners.length ? 'is-current' : ''}`}>
                    <span>{index + 1}</span><strong>${amount}</strong>
                    {owners.map(owner => {
                        const player = snapshot.game.players.find(candidate => candidate.id === owner.player);
                        return player && <span key={owner.player} title={playerName(snapshot, owner.player)}>
                            <Emblem player={player} size={15}/><span className="sr-only">{playerName(snapshot, owner.player)}</span>
                        </span>;
                    })}
                </li>;
            })}
        </ol><p className="small muted">{t('table.payoutOverflow')}</p>
    </details>;
}

export function UnplayedLeaders({snapshot, advance}: {snapshot: Snapshot; advance: string}) {
    const availability = snapshot.game.unplayedAdvanceLeaders?.find(candidate => candidate.advance === advance);
    if (!availability) return null;
    return <span className="unplayed-leaders" title={t('table.unplayedLeadersHint')}>
        {t('table.unplayedLeaders')} · {availability.epochs.map(epoch => `${['I', 'II', 'III'][epoch.epoch - 1]}: ${epoch.count}`).join(' · ')}
    </span>;
}

export function Markets({snapshot}: {snapshot: Snapshot}) {
    return <section className="markets-workspace" aria-label={t('table.markets')}>
        <div className="market-grid">{snapshot.game.commodityPayouts.map(payout => <details className="market-tile" data-commodity={payout.commodity} key={payout.commodity}>
            <summary><Commodity commodity={payout.commodity}/>
                <CommodityAdjustments payout={payout}/><Payouts snapshot={snapshot} payout={payout}/></summary>
            <div className="market-detail">{payout.owners.map(owner => <div key={owner.player}>
                <strong>{playerName(snapshot, owner.player)} · ${owner.payout}</strong>
                <p>{t('table.markets')}: {owner.marketCount} · {t('table.payoutBox')}: {owner.effectiveBox}</p>
                <p>{owner.provinces.map(provinceName).join(', ')}</p>
                {owner.industryBonus > 0 && <p>{advanceName('industry')}: +{owner.industryBonus} {t('table.payoutBox')}</p>}
            </div>)}{payout.owners.length === 0 && <p>{t('table.noOwners')}</p>}
                <PayoutReference snapshot={snapshot} payout={payout}/>
            </div>
        </details>)}</div>
    </section>;
}

export function Hand({snapshot}: {snapshot: Snapshot}) {
    const {selectedCard, setSelectedCard, setSelected, setSelectedAdvance, setPicker, inspection, setInspection} = useMapSelection();
    return <section className="table-hand" aria-label={t('game.hand')}>
        <div className="hand-heading"><p className="eyebrow">{t('game.hand')}</p><strong>{snapshot.game.hand.length} {t('game.cards').toLowerCase()}</strong></div>
        <div className="hand-cards">{snapshot.game.hand.map(card => {
            const offer = snapshot.game.cardOffers.find(candidate => candidate.reference === card.reference);
            return <button type="button" key={card.reference} data-hand-card={card.reference}
                aria-pressed={selectedCard === card.reference || (inspection?.kind === 'card' && inspection.id === card.reference)} className={`hand-card ${card.definition.kind}`}
                onClick={() => {
                    if (!offer?.playable) {
                        setInspection({kind: 'card', id: card.reference});
                        return;
                    }
                    setInspection(null); setSelectedCard(card.reference); setSelected(''); setSelectedAdvance('');
                    if (selectedCard !== card.reference) setPicker(null);
                }}>
                <span className="eyebrow">{t(`kind.${card.definition.kind}`)}</span><h3>{t(card.definition.messageId)}</h3>
                <CardEventText definition={card.definition}/>
                {snapshot.game.burdenReferences.includes(card.reference) && <span className="burden-badge">{t('game.burden')}</span>}
                {offer?.commodities.map(commodity => {
                    const payout = snapshot.game.commodityPayouts.find(candidate => candidate.commodity === commodity);
                    return <span className="card-commodity" key={commodity}><Commodity commodity={commodity}/>{payout && <>
                        <CommodityAdjustments payout={payout}/><Payouts snapshot={snapshot} payout={payout}/>
                    </>}</span>;
                })}
                {offer?.leaderDiscounts.map(discount => <span className="leader-discount" key={discount.advance}>
                    <span>{advanceName(discount.advance)}</span><strong>−${discount.effectiveDiscount}</strong>
                </span>)}
            </button>;
        })}{snapshot.game.hand.length === 0 && <p className="muted">{t('game.noCards')}</p>}</div>
    </section>;
}

export function AdvanceTiles({snapshot}: {snapshot: Snapshot}) {
    const {selectedAdvance, setSelectedAdvance, setSelected, setSelectedCard, setPicker, inspection, setInspection} = useMapSelection();
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const advances = snapshot.game.catalog?.advances ?? [];
    const categories = [...new Set(advances.map(advance => advance.category))];
    return <section className="advances-workspace" aria-label={t('game.advances')}>
        <PlayedLeaders snapshot={snapshot}/>
        {categories.map(category => <div className="advance-category" key={category}>
            <h3>{label(category, 'category')} <small>{advances.filter(advance => advance.category === category && me?.advances.includes(advance.id)).length}</small></h3>
            <div className="advance-tiles">{advances.filter(advance => advance.category === category).map(advance => {
                const offer = snapshot.game.advanceOffers.find(candidate => candidate.advance === advance.id);
                const owned = me?.advances.includes(advance.id);
                const available = offer?.unavailableReason === null && snapshot.legalChoices.commands.includes(`buy-advance ${advance.letter}`);
                return <button type="button" data-advance={advance.id} className={`advance-tile ${owned ? 'owned' : ''} ${available ? 'available' : ''} ${offer?.leaderDiscounts?.length ? 'discounted' : ''}`} key={advance.id}
                    aria-pressed={selectedAdvance === advance.id || (inspection?.kind === 'advance' && inspection.id === advance.id)} onClick={() => {
                        if (!available) {
                            setInspection({kind: 'advance', id: advance.id});
                            return;
                        }
                        setInspection(null); setSelectedAdvance(advance.id); setSelected(''); setSelectedCard(''); setPicker(null);
                    }}>
                    <span className="advance-letter">{advance.letter}</span><strong>{advanceName(advance.id)}</strong>
                    <span>{owned ? t('table.owned') : offer?.price !== null && offer?.price !== undefined ? `$${offer.price}` : '—'}</span>
                    <UnplayedLeaders snapshot={snapshot} advance={advance.id}/>
                    {available && <small className="advance-available">{t('table.available')}</small>}
                    {offer?.leaderDiscounts?.map(discount => <small className="advance-discount" key={discount.leaderCardId}>{label(discount.leaderCardId, 'leader')} −${discount.amount}</small>)}
                    {offer?.unavailableReason && <small>{t(`error.${offer.unavailableReason}`)}</small>}
                </button>;
            })}</div>
        </div>)}
    </section>;
}

export function PlayedLeaders({snapshot}: {snapshot: Snapshot}) {
    const leaders = snapshot.game.effects.leaders;
    if (!leaders.length) return null;
    return <section className="leader-list" aria-label={t('game.leaders')}><h3>{t('game.leaders')}</h3>{leaders.map(leader => {
        const credits = snapshot.game.playedLeaderDiscounts?.find(candidate => candidate.leader === leader.leader);
        const inBox = leader.role === 'DISPLAY';
        return <article key={leader.leader} className={inBox ? 'leader-in-box' : 'leader-exposed'} data-played-leader={leader.leader}>
            <span className="leader-role">{inBox ? t('table.inTheBox') : label(leader.role, 'leaderRole')}
                {leader.displayBox && ` · ${t('game.displayBox')} ${leader.displayBox}`}</span>
            <strong>{label(leader.leader, 'leader')}</strong>
            <p className="small">{playerName(snapshot, leader.owner)}</p>
            {credits && <><p className="leader-credits-label">{t(credits.usable ? 'table.yourLeaderCredits' : 'table.leaderCredits')}</p>
                {credits.discounts.map(discount => <div className="leader-discount" key={discount.advance}>
                    <span>{advanceName(discount.advance)}</span><strong>−${credits.usable ? discount.effectiveDiscount : discount.conditionalAmount}</strong>
                </div>)}</>}
            {leader.role === 'EXPOSED' && snapshot.game.rules.patronage === 'PROTECTION_MONEY' && <p className="small">{t('game.protection')}: ${leader.protectionCash}</p>}
            {leader.patronageClaimants.length > 0 && <p className="small">{t('game.claimants')}: {leader.patronageClaimants.map(id => playerName(snapshot, id)).join(', ')}</p>}
        </article>;
    })}</section>;
}
