import {useState} from 'react';
import type {Snapshot} from './api';
import {label, playerName, provinceName} from './labels';
import {t} from './messages';
import {MapPicker, useMapSelection} from './map/MapSelection';
import {ChoiceButtons, InvalidChoice, useTargets} from './DecisionControls';
import {MiseryBox} from './MiseryBox';

interface ActionProps {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>}

const simpleActions = ['pass', 'buy-card', 'buy-expansion-card', 'windmill', 'written-record', 'cathedral', 'stabilize cash', 'stabilize misery'];

export function GameActions({snapshot, busy, submit}: ActionProps) {
    const {setSelectedCard, setSelected, setSelectedAdvance, setInspection} = useMapSelection();
    const [selectedAction, setSelectedAction] = useState('');
    const [relief, setRelief] = useState('');
    const player = snapshot.game.players.find(candidate => candidate.id === snapshot.viewerId);
    if (!player) return null;
    const commands = snapshot.legalChoices.commands;
    const finite = commands.filter(command => !simpleActions.includes(command) &&
        (command.startsWith('written-record ') || command.startsWith('swap-order ') || command.startsWith('cancel-adjustment ') ||
            command.startsWith('return-card ') || command.startsWith('adjust-market ') || command.startsWith('discard-card ') || command.startsWith('civil-war-loss ') ||
            command.startsWith('replace-event-token ') || command.startsWith('cathedral ') || command.startsWith('patronize ') || command.startsWith('offer-card ') || command.startsWith('reduce-marker ')));
    const finiteLabel = (command: string) => {
        const [action, first, second] = command.split(' ');
        if (action === 'written-record') return `${t('action.written-record')} · ${playerName(snapshot, first)}`;
        if (action === 'swap-order') return `${t('action.swapOrder')} ${playerName(snapshot, first)}`;
        if (action === 'cancel-adjustment') {
            const cost = snapshot.game.catalog?.commodities.find(commodity => commodity.id === first.toUpperCase())?.baseValue;
            const shared = ['gold', 'ivory'].includes(first) && ['GOLD', 'IVORY'].every(commodity =>
                snapshot.game.adjustments[commodity]?.some(adjustment => adjustment.toLowerCase() === second));
            const commodity = shared ? t('table.goldIvory') : label(first.toUpperCase(), 'commodity');
            return `${t('action.cancel')} ${commodity} ${label(second.toUpperCase(), 'adjustment')} · $${cost ?? '—'}`;
        }
        if (action === 'discard-card' || action === 'offer-card' || action === 'return-card') {
            const card = snapshot.game.hand.find(card => card.reference === first);
            const actionKey = action === 'discard-card' ? 'action.discard' : action === 'return-card' ? 'action.returnCard' : 'action.offerCard';
            return `${t(actionKey)} ${card ? t(card.definition.messageId) : first}`;
        }
        if (action === 'adjust-market') return `${label(first.toUpperCase(), 'commodity')} · ${label(second.toUpperCase(), 'adjustment')}`;
        if (action === 'patronize') return `${t('action.patronize')} ${label(first, 'leader')}`;
        if (action === 'reduce-marker') return `${t('action.reduceMarker')} ${provinceName(first)}`;
        if (action === 'replace-event-token') {
            const replacement = snapshot.game.replacementToken;
            if (!replacement) return t(first === 'pool' ? 'replacement.usePool' : 'replacement.leaveEmptyGeneric');
            const province = provinceName(replacement.province);
            return first === 'pool'
                ? t('replacement.placePoolToken', {count: replacement.poolCost, province})
                : t('replacement.leaveEmpty', {province});
        }
        return `${label(action, 'action')} ${label(first)}`;
    };
    return <div className="game-actions">
        {snapshot.game.replacementToken && <ReplacementTokenContext snapshot={snapshot}/>}
        {snapshot.game.activity?.purchaseStep === 'STABILIZATION' && <p>{t('controls.stabilizationCost')}: <strong>${snapshot.game.stabilizationCost}</strong></p>}
        <div className="action-buttons">{commands.filter(command => simpleActions.includes(command)).map(command => <button key={command} disabled={busy} onClick={() => { void submit(command); }}>{label(command, 'action')}
            {command === 'buy-expansion-card' && <> · {snapshot.game.expansionCardCost ?? '—'} {t('table.tokens')}</>}
            {command === 'stabilize misery' && <> · <span className="misery-transition"><MiseryBox value={player.misery} track={snapshot.game.catalog?.miseryTrack}/> → <MiseryBox value={snapshot.game.stabilizationMisery} track={snapshot.game.catalog?.miseryTrack}/></span></>}</button>)}
            {finite.filter(command => !command.startsWith('reduce-marker ')).map(command => <button type="button" className="secondary" key={command} disabled={busy} aria-pressed={selectedAction === command} onClick={() => setSelectedAction(command)}>{finiteLabel(command)}</button>)}
        </div>
        {['PLAY_CARDS', 'FINAL_CARD_PLAY'].includes(snapshot.game.stage) && <div className="decision-cards">{snapshot.game.cardOffers.filter(offer => offer.playable).map(offer => {
            const card = snapshot.game.hand.find(card => card.reference === offer.reference);
            return card && <button type="button" className="secondary eligible-card" key={offer.reference} data-eligible-card={offer.reference}
                disabled={busy} onClick={() => {setInspection(null); setSelectedCard(offer.reference); setSelected(''); setSelectedAdvance('');}}>{t(card.definition.messageId)}</button>;
        })}</div>}
        {finite.some(command => command.startsWith('reduce-marker ')) && <details className="marker-reduction"><summary>{t('action.reduceMarker')}</summary>
            <div className="action-buttons">{finite.filter(command => command.startsWith('reduce-marker ')).map(command =>
                <button type="button" className="secondary" key={command} disabled={busy} aria-pressed={selectedAction === command}
                    onClick={() => setSelectedAction(command)}>{finiteLabel(command)}</button>)}</div>
        </details>}
        {selectedAction && <div className="selected-action"><p>{finiteLabel(selectedAction)}</p><InvalidChoice invalid={!finite.includes(selectedAction)} clear={() => setSelectedAction('')}/><button disabled={busy || !finite.includes(selectedAction)} onClick={async () => { if (await submit(selectedAction)) setSelectedAction(''); }}>{t('table.confirm')}</button></div>}
        {snapshot.game.shippingOffer && <div className="purchase-preview">
            <p>{t('game.ships')}: {snapshot.game.shippingOffer.ships ? `${label(snapshot.game.shippingOffer.ships.fleet, 'fleet')} ${snapshot.game.shippingOffer.ships.level}` : '—'}</p>
            <p>{t('controls.purchaseCost')}: ${snapshot.game.shippingOffer.cost} · {t('controls.remainingCash')}: ${snapshot.game.shippingOffer.cashAfter ?? '—'}</p>
            {snapshot.game.shippingOffer.blockers.map(blocker => <p key={blocker} className="blocker">{t(`error.${blocker}`)}</p>)}
            <button disabled={busy || !commands.includes('upgrade-ships') || snapshot.game.shippingOffer.blockers.length > 0} onClick={() => { void submit('upgrade-ships'); }}>{t('action.upgrade-ships')}</button>
        </div>}
        {snapshot.game.activity?.purchaseStep === 'RELIEF' && <form className="guided-form" onSubmit={event => { event.preventDefault(); if (!busy && snapshot.game.reliefOffers.some(offer => String(offer.spaces) === relief && offer.affordable)) void submit(`relieve-misery ${relief}`); }}>
            <label htmlFor="relief-spaces">{t('action.relieveMisery')}</label>
            <ChoiceButtons id="relief-spaces" value={relief} onChange={setRelief} disabled={busy}
                choices={snapshot.game.reliefOffers.map(offer => ({value: String(offer.spaces), disabled: !offer.affordable,
                    label: `${offer.spaces} ${t('controls.spaces')} · $${offer.cashCost} · ${offer.ratingSpent} ${t('controls.mrUsed')}`}))}/>
            <button disabled={busy || !snapshot.game.reliefOffers.some(offer => String(offer.spaces) === relief && offer.affordable)}>{t('action.relieveMisery')}</button>
            {snapshot.game.reliefOffers.length === 0 ? <p className="small muted">{t('controls.noRelief')}</p> : !snapshot.game.reliefOffers.some(offer => offer.affordable) && <p className="blocker">{t('error.INSUFFICIENT_CASH')}</p>}
        </form>}
        {commands.includes('forgo-marker') && <EventMarkerPlacement snapshot={snapshot} busy={busy} submit={submit}/>}
        {commands.some(command => command.startsWith('cede-war')) && <WarSurrender snapshot={snapshot} busy={busy} submit={submit}/>}
    </div>;
}

function ReplacementTokenContext({snapshot}: {snapshot: Snapshot}) {
    const replacement = snapshot.game.replacementToken;
    if (!replacement) return null;
    const province = provinceName(replacement.province);
    return <section className="replacement-token-context" aria-label={t('action.replace-event-token')}>
        <h3>{province}</h3>
        <p>{t('replacement.context', {event: label(replacement.event, 'event'), player: playerName(snapshot, replacement.player), province})}</p>
        <p>{t(replacement.poolAfter === null ? 'replacement.noPool' : 'replacement.noStock')}</p>
        {replacement.poolAfter !== null && <>
            <p>{t('replacement.poolBalance', {before: replacement.poolTokens, after: replacement.poolAfter})}</p>
            <p>{t('replacement.coloredToken', {province})}</p>
        </>}
        <p>{t('game.misery')}: <MiseryBox value={replacement.misery} track={snapshot.game.catalog?.miseryTrack}/></p>
        {replacement.event === 'CIVIL_WAR' && <p>{t('replacement.civilWarMiseryApplied')}</p>}
        <p className="small muted">{t('replacement.noMisery')}</p>
        {replacement.civilWarLossPending && <p>{t('replacement.civilWarLossPending')}</p>}
    </section>;
}

function EventMarkerPlacement({snapshot, busy, submit}: ActionProps) {
    const [source, setSource] = useState('');
    const destination = snapshot.game.markerPlacement?.destination;
    const sources = snapshot.legalChoices.commands.filter(command => command.startsWith('move-marker '));
    return <div className="guided-form">
        <h3>{t('controls.markerPlacement')}{destination ? ` · ${provinceName(destination)}` : ''}</h3>

        <button className="secondary" disabled={busy} onClick={async () => { if (await submit('forgo-marker')) setSource(''); }}>{t('action.forgo-marker')}</button>
        {sources.length > 0 && <form onSubmit={async event => {
            event.preventDefault();
            if (!busy && sources.includes(source) && await submit(source)) setSource('');
        }}>
            <label htmlFor="marker-source">{t('controls.markerSource')}</label>
            <ChoiceButtons id="marker-source" value={source} onChange={setSource} disabled={busy}
                choices={sources.map(command => ({value: command, label: provinceName(command.split(' ')[1])}))}/>
            <button disabled={busy || !sources.includes(source)}>{t('action.move-marker')}</button>
            <InvalidChoice invalid={!!source && !sources.includes(source)} clear={() => setSource('')}/>
            <MapPicker title={t('controls.chooseMarkerSource')} eligible={sources.map(command => command.split(' ')[1])}
                selected={source ? [source.split(' ')[1]] : []} onPick={id => setSource(`move-marker ${id}`)} disabled={busy}/>
        </form>}
    </div>;
}

function WarSurrender({snapshot, busy, submit}: ActionProps) {
    const cession = snapshot.game.warCession;
    const required = cession?.requiredCount ?? 0;
    const provinces = snapshot.game.catalog?.provinces.filter(province => cession?.eligibleProvinces.includes(province.id)) ?? [];
    const selection = useTargets(cession?.eligibleProvinces ?? [], required, [
        {eligible: provinces.filter(province => province.overseas).map(province => province.id), capacity: cession?.overseasCapacity ?? 0},
    ]);
    const selected = selection.selected;
    const winner = snapshot.game.players.find(player => player.id === cession?.winner);
    const validSelection = !!cession && selection.valid;
    return <form className="guided-form" onSubmit={event => { event.preventDefault(); if (!busy && validSelection) void submit(`cede-war ${selected.join(' ')}`.trim()); }}>
        <h3>{t('action.cedeWar')} · {selected.length}/{required}</h3><p>{t('controls.warWinner')}: {winner ? playerName(snapshot, winner.id) : '—'}. {t('controls.overseasCapacity')}: {cession?.overseasCapacity}.</p>
        <div className="province-checks">{provinces.map(province => <label className="check-label" key={province.id}><input type="checkbox" checked={selected.includes(province.id)} disabled={busy || !selection.canPick(province.id)} onChange={() => selection.pick(province.id)}/>{provinceName(province.id)}</label>)}</div>
        <MapPicker title={t('controls.chooseCession').replace('{count}', String(required))} selected={selected} disabled={busy}
            eligible={provinces.filter(province => selection.canPick(province.id)).map(province => province.id)} onPick={selection.pick}/>
        <InvalidChoice invalid={selection.invalid} clear={selection.clear}/>
        <button disabled={busy || !validSelection}>{t('action.cedeWar')}</button>
    </form>;
}
