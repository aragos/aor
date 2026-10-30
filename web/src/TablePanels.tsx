import {useLayoutEffect, useRef} from 'react';
import type {CommodityPayoutView, PlayerView, PublicCatalog, Snapshot} from './api';
import {PlayerLabel, PlayerLabels, ProvinceLabel} from './IdentityLabel';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';
import {TableIcon} from './TableIcon';
import {HelpLink} from './HelpLink';
import {MiseryBox} from './MiseryBox';
import {CommodityIcon, PlayerPiece} from './map/MapSymbols';
import {useMapSelection} from './map/MapSelection';
import {LeaderCredit} from './LeaderCredit';
import {AdvancePrice} from './AdvancePrice';
import {finiteDecisions, leaderReference} from './decisionOffers';

export function Emblem({player, size = 20}: {player: Pick<PlayerView, 'capital'>; size?: number}) {
    return <svg width={size} height={size} viewBox="-28 -28 56 56" aria-hidden="true">
        <PlayerPiece capital={player.capital} size={44}/>
    </svg>;
}

export function Commodity({commodity}: {commodity: string}) {
    return <span className="commodity-name"><svg width="24" height="24" viewBox="0 0 24 24" aria-hidden="true">
        <CommodityIcon commodity={commodity}/></svg>{label(commodity, 'commodity')}</span>;
}

/** The native top layer keeps expanded details outside the strip’s layout and clipping. */
function PlayerDetails({player, catalog}: {player: PlayerView; catalog: PublicCatalog | null}) {
    const ref = useRef<HTMLDivElement>(null);
    useLayoutEffect(() => {
        const panel = ref.current;
        const details = panel?.closest('details');
        if (!panel || !details) return;
        panel.popover = 'auto';
        const position = () => {
            const summary = details.querySelector('summary');
            if (!summary) return;
            const bounds = summary.getBoundingClientRect();
            const width = Math.min(320, innerWidth - 24);
            const top = Math.min(bounds.bottom + 6, innerHeight - 100);
            Object.assign(panel.style, {width: `${width}px`, top: `${top}px`,
                left: `${Math.max(12, Math.min(bounds.left, innerWidth - width - 12))}px`,
                maxHeight: `${innerHeight - top - 12}px`});
        };
        const sync = () => {
            if (details.open) {position(); panel.showPopover();}
            else panel.hidePopover();
        };
        const dismissed = () => {
            if (!panel.matches(':popover-open')) details.open = false;
        };
        details.addEventListener('toggle', sync);
        panel.addEventListener('toggle', dismissed);
        window.addEventListener('resize', position);
        document.addEventListener('scroll', position, true);
        return () => {
            details.removeEventListener('toggle', sync);
            panel.removeEventListener('toggle', dismissed);
            window.removeEventListener('resize', position);
            document.removeEventListener('scroll', position, true);
            panel.hidePopover();
        };
    }, []);
    const advances = catalog?.advances.filter(advance => player.advances.includes(advance.id));
    const categories = [...new Set(advances?.map(advance => advance.category))];
    return <div ref={ref} className="player-detail" role="region" aria-label={player.name}>
        <button type="button" className="close-detail" aria-label={t('table.close')} onClick={() => ref.current?.hidePopover()}>×</button>
        <dl className="detail-list">
            <div className="written-cash"><dt title={t('game.writtenCashHelp')}>{t('game.writtenCash')}</dt><dd>${player.writtenCash}</dd></div>
            {player.poolAvailable && <div className="player-pool-detail"><dt>{t('game.pool')}</dt><dd>{player.poolTokens}</dd></div>}
            <div><dt>{t('game.tokens')}</dt><dd>{player.stockTokens}</dd></div>
            <div><dt>{t('game.boardMarkers')}</dt><dd>{player.boardMarkers}</dd></div>
        </dl>
        <h4>{t('game.advances')}</h4>
        {advances ? categories.map(category => <section className="player-advance-category" data-advance-category={category} key={category}>
            <h5>{label(category, 'category')}</h5>
            <ul className="plain-list">{advances.filter(advance => advance.category === category).map(advance => <li key={advance.id}>{advanceName(advance.id)}</li>)}</ul>
        </section>) : <ul className="plain-list">{player.advances.map(advance => <li key={advance}>{advanceName(advance)}</li>)}</ul>}
    </div>;
}

export function PlayerOverview({snapshot}: {snapshot: Snapshot}) {
    const {playerPicker, selectedAction, setSelectedAction} = useMapSelection();
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
            const swap = finiteDecisions(snapshot).find(offer => offer.kind === 'order-swap' && offer.subject.id === player.id)?.command;
            const eligible = playerPicker?.eligible.includes(player.id) || !!swap;
            const picked = playerPicker?.selected.includes(player.id) || (!!swap && selectedAction === swap);
            const person = snapshot.accountId ? player.name.replace(/^@/, '') : player.name;
            const whiteMarkers = snapshot.game.board.filter(province => province.marker?.owner === player.id && province.marker.side === 'WHITE').length;
            return <details data-player={player.id} key={player.id} className={`player-summary ${player.id === snapshot.viewerId ? 'is-viewer' : ''} ${active ? 'is-active' : ''} ${eligible ? 'eligible-target' : ''} ${picked ? 'picked-target' : ''}`}>
                <summary aria-pressed={eligible ? picked : undefined} onClick={event => {
                    if (event.target instanceof Element && event.target.closest('button, a')) return;
                    if (eligible) { if (swap) setSelectedAction(swap); else playerPicker?.pick(player.id);}
                }} aria-label={`${playerName(snapshot, player.id)}, ${player.name}, ${status}, ${t('game.cash')} $${player.cash}, ${t('table.income')} ${player.income ? `$${player.income.ordinary}` : t('table.unavailable')}, ${t('game.misery')} ${player.misery ?? t('game.chaos')}, ${t('game.ships')} ${fleet}, ${player.handCount} ${t('game.cards')}, ${player.advances.length} ${t('game.advances')}`}>
                    <span className="player-identity"><Emblem player={player}/><span className="player-labels"><strong>{player.capital ? provinceName(player.capital) : person}</strong>
                        {player.capital && <span className="player-person" title={player.name}>{person}</span>}</span>
                        <small>{player.id === snapshot.viewerId ? t('table.you') : order.includes(player.id) ? String(order.indexOf(player.id) + 1).padStart(2, '0') : ''}</small></span>
                    <span className="player-money"><span title={t('game.cash')} aria-label={`${t('game.cash')}: $${player.cash}`}><TableIcon kind="cash"/> <b>${player.cash}</b></span>
                        <span className="income-value" title={t('table.income')} aria-label={`${t('table.income')}: ${player.income ? `$${player.income.ordinary}` : t('table.unavailable')}`}><TableIcon kind="income"/> <b>{player.income ? `$${player.income.ordinary}` : '—'}</b></span>
                        <span className="player-advance-count" title={t('game.advances')} aria-label={`${t('game.advances')}: ${player.advances.length}`}><TableIcon kind="advances"/> <b>{player.advances.length}</b></span></span>
                    <span className="player-stats"><span title={t('game.misery')}><TableIcon kind="misery"/> <MiseryBox value={player.misery} track={snapshot.game.catalog?.miseryTrack}/><HelpLink topic="misery-track" label={t('table.miseryTrack')} inline/></span>
                        <span title={t('game.cards')}><TableIcon kind="cards"/> {player.handCount}</span>
                        <span title={t('game.ships')}><TableIcon kind="ship"/> {fleet}<HelpLink topic="shipping-track" label={t('table.shippingTrack')} inline/></span></span>
                    <span className="player-status-row">
                        {player.poolAvailable && <span className="player-pool" title={t('table.poolTokens')} aria-label={`${t('table.poolTokens')}: ${player.poolTokens}`}><TableIcon kind="token"/><b>{player.poolTokens}</b><small>{t('table.poolTokens')}</small></span>}
                        {!finished && <span className="player-status">{status}</span>}
                    </span>
                    {snapshot.game.stage === 'EXPANSION' && <span className="player-white-markers" aria-label={`${t('table.whiteMarkers')}: ${whiteMarkers}`}>
                        <b>{whiteMarkers}</b><small>{t('table.whiteMarkers')}</small>
                    </span>}
                    {eligible && <span className="target-status">{t(picked ? 'table.selectedTarget' : 'table.legalTarget')}</span>}
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
    if (!snapshot.game.catalog) return null;
    return <HelpLink topic={`commodity-${payout.commodity.toLowerCase()}`} label={t('table.fullPayouts')} inline/>;
}

export function CommodityValueNote({snapshot}: {snapshot: Snapshot}) {
    return snapshot.game.board.some(province => province.marker?.side === 'WHITE')
        ? <p className="small muted commodity-value-note">{t('table.currentCommodityControl')}</p> : null;
}

export function UnplayedLeaders({snapshot, advance}: {snapshot: Snapshot; advance: string}) {
    const {setInspection} = useMapSelection();
    const availability = snapshot.game.unplayedAdvanceLeaders?.find(candidate => candidate.advance === advance);
    if (!availability?.epochs.some(epoch => epoch.leaders.length)) return null;
    return <span className="unplayed-leaders" title={t('table.unplayedLeadersHint')}>
        <span>{t('table.unplayedLeaders')}</span>
        {availability.epochs.flatMap(epoch => epoch.leaders.map(leader => {
            const name = label(leader, 'leader');
            const printedEpoch = ['I', 'II', 'III'][epoch.epoch - 1];
            return <button type="button" className="unplayed-leader" key={leader}
                data-unplayed-leader={leader} title={`${name} · ${t('game.epoch')} ${printedEpoch}`}
                aria-label={`${name} · ${t('game.epoch')} ${printedEpoch}`}
                onClick={() => setInspection({kind: 'history-card', id: leaderReference(leader)})}>
                {printedEpoch}
            </button>;
        }))}
    </span>;
}

export function Markets({snapshot}: {snapshot: Snapshot}) {
    const {selectedAction, setSelectedAction} = useMapSelection();
    return <section className="markets-workspace" aria-label={t('table.markets')}>
        <CommodityValueNote snapshot={snapshot}/>
        <div className="market-grid">{snapshot.game.commodityPayouts.map(payout => <details className="market-tile" data-commodity={payout.commodity} key={payout.commodity}>
            <summary><Commodity commodity={payout.commodity}/>
                <CommodityAdjustments payout={payout}/><Payouts snapshot={snapshot} payout={payout}/></summary>
            <div className="market-detail">{snapshot.legalChoices.commands.filter(command => command.startsWith(`cancel-adjustment ${payout.commodity.toLowerCase()} `) ||
                (payout.commodity === 'IVORY' && command.startsWith('cancel-adjustment gold '))).map(command => <button type="button" className="secondary" key={command} aria-pressed={selectedAction === command} onClick={() => setSelectedAction(command)}>{t('action.cancel')} {label(command.split(' ')[2].toUpperCase(), 'adjustment')}</button>)}{payout.owners.map(owner => <div key={owner.player}>
                <strong><PlayerLabel snapshot={snapshot} id={owner.player}/> · ${owner.payout}</strong>
                <p>{t('table.markets')}: {owner.marketCount} · {t('table.payoutBox')}: {owner.effectiveBox}</p>
                <p>{owner.provinces.map((id, index) => <span key={id}>{index > 0 && ', '}<ProvinceLabel snapshot={snapshot} id={id}/></span>)}</p>
                {owner.industryBonus > 0 && <p>{advanceName('industry')}: +{owner.industryBonus} {t('table.payoutBox')}</p>}
            </div>)}{payout.owners.length === 0 && <p>{t('table.noOwners')}</p>}
                <PayoutReference snapshot={snapshot} payout={payout}/>
            </div>
        </details>)}</div>
    </section>;
}

export function AdvanceTiles({snapshot}: {snapshot: Snapshot}) {
    const {selectedAdvance, setSelectedAdvance, setSelected, setSelectedCard, setPicker, setInspection, setSelectedAction} = useMapSelection();
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
                return <div className={`object-control advance-control ${owned ? 'owned' : ''} ${available ? 'available' : ''}`} key={advance.id}><button type="button" data-advance={advance.id} className={`advance-tile ${owned ? 'owned' : ''} ${available ? 'available' : ''} ${offer?.leaderDiscounts?.length ? 'discounted' : ''}`} key={advance.id}
                    aria-pressed={selectedAdvance === advance.id} onClick={() => {
                        if (!available) {
                            setInspection({kind: 'advance', id: advance.id});
                            return;
                        }
                        setSelectedAction(''); setInspection(null); setSelectedAdvance(advance.id); setSelected(''); setSelectedCard(''); setPicker(null);
                    }}>
                    <span className="advance-letter">{advance.letter}</span><strong>{advanceName(advance.id)}</strong>
                    <AdvancePrice offer={offer} printedCost={advance.cost} compact/>
                    {owned && <small className="advance-owned">{t('table.owned')}</small>}
                    {available && <small className="advance-available">{t('table.available')}</small>}
                    {offer?.unavailableReason && <small>{t(`error.${offer.unavailableReason}`)}</small>}
                </button><UnplayedLeaders snapshot={snapshot} advance={advance.id}/></div>;
            })}</div>
        </div>)}
    </section>;
}

export function PlayedLeaders({snapshot}: {snapshot: Snapshot}) {
    const {selectedAction, setSelectedAction, setInspection} = useMapSelection();
    const leaders = snapshot.game.effects.leaders;
    if (!leaders.length) return null;
    return <section className="leader-list" aria-label={t('game.leaders')}><h3>{t('game.leaders')}</h3>{leaders.map(leader => {
        const credits = snapshot.game.playedLeaderDiscounts?.find(candidate => candidate.leader === leader.leader);
        const inBox = leader.role === 'DISPLAY';
        const claim = finiteDecisions(snapshot).find(offer => offer.kind === 'patronage' && offer.subject.id === leaderReference(leader.leader));
        return <article key={leader.leader} className={inBox ? 'leader-in-box' : 'leader-exposed'} data-played-leader={leader.leader}>
            <span className="leader-role">{inBox ? t('table.inTheBox') : label(leader.role, 'leaderRole')}</span>
            <button type="button" className="map-link" onClick={() => setInspection({kind: 'history-card', id: leaderReference(leader.leader)})}>{label(leader.leader, 'leader')}</button>
            {claim && <button type="button" className="secondary" aria-pressed={selectedAction === claim.command} onClick={() => setSelectedAction(claim.command)}>{t('action.patronize')}</button>}
            <p className="small"><PlayerLabel snapshot={snapshot} id={leader.owner}/></p>
            {credits && <><p className="leader-credits-label">{t(credits.usable ? 'table.yourLeaderCredits' : 'table.leaderCredits')}</p>
                {credits.discounts.map(discount => <div className="leader-discount" key={discount.advance}>
                    <span>{advanceName(discount.advance)}</span><LeaderCredit amount={credits.usable ? discount.effectiveDiscount : discount.conditionalAmount}
                        writtenRecordBonus={discount.writtenRecordBonus} includesWrittenRecord={credits.usable}/>
                </div>)}</>}
            {leader.role === 'EXPOSED' && <p className="small">{t('game.protection')}: ${leader.protectionCash}</p>}
            {leader.patronageClaimants.length > 0 && <p className="small">{t('game.claimants')}: <PlayerLabels snapshot={snapshot} ids={leader.patronageClaimants}/></p>}
        </article>;
    })}</section>;
}
