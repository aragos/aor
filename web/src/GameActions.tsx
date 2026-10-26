import {useState} from 'react';
import type {ProvinceDefinition, Snapshot} from './api';
import {label, playerName, provinceName} from './labels';
import {t} from './messages';
import {MapPicker} from './map/MapSelection';

interface ActionProps {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>}

const simpleActions = ['pass', 'buy-card', 'buy-expansion-card', 'windmill', 'written-record', 'cathedral', 'stabilize cash', 'stabilize misery'];

export function GameActions({snapshot, busy, submit}: ActionProps) {
    const [selectedAction, setSelectedAction] = useState('');
    const [relief, setRelief] = useState('');
    const commands = snapshot.legalChoices.commands;
    const finite = commands.filter(command => !simpleActions.includes(command) &&
        (command.startsWith('written-record ') || command.startsWith('swap-order ') || command.startsWith('cancel-adjustment ') ||
            command.startsWith('return-card ') || command.startsWith('adjust-market ') || command.startsWith('discard-card ') || command.startsWith('civil-war-loss ') ||
            command.startsWith('replace-event-token ') || command.startsWith('cathedral ') || command.startsWith('patronize ') || command.startsWith('offer-card ') || command.startsWith('reduce-marker ')));
    const finiteLabel = (command: string) => {
        const [action, first, second] = command.split(' ');
        if (action === 'written-record') return `${t('action.written-record')} · ${playerName(snapshot, first)}`;
        if (action === 'swap-order') return `${t('action.swapOrder')} ${playerName(snapshot, first)}`;
        if (action === 'cancel-adjustment') return `${t('action.cancel')} ${label(first.toUpperCase(), 'commodity')} ${label(second.toUpperCase(), 'adjustment')}`;
        if (action === 'discard-card' || action === 'offer-card' || action === 'return-card') {
            const card = snapshot.game.hand.find(card => card.reference === first);
            const actionKey = action === 'discard-card' ? 'action.discard' : action === 'return-card' ? 'action.returnCard' : 'action.offerCard';
            return `${t(actionKey)} ${card ? t(card.definition.messageId) : first}`;
        }
        if (action === 'adjust-market') return `${label(first.toUpperCase(), 'commodity')} · ${label(second.toUpperCase(), 'adjustment')}`;
        if (action === 'patronize') return `${t('action.patronize')} ${label(first, 'leader')}`;
        if (action === 'reduce-marker') return `${t('action.reduceMarker')} ${provinceName(first)}`;
        return `${label(action, 'action')} ${label(first)}`;
    };
    return <div className="game-actions">
        {snapshot.game.activity?.purchaseStep === 'STABILIZATION' && <p>{t('controls.stabilizationCost')}: <strong>${snapshot.game.stabilizationCost}</strong></p>}
        <div className="action-buttons">{commands.filter(command => simpleActions.includes(command)).map(command => <button key={command} disabled={busy} onClick={() => { void submit(command); }}>{label(command, 'action')}</button>)}
            {finite.map(command => <button type="button" className="secondary" key={command} disabled={busy} aria-pressed={selectedAction === command} onClick={() => setSelectedAction(command)}>{finiteLabel(command)}</button>)}
        </div>
        {selectedAction && <div className="selected-action"><p>{finiteLabel(selectedAction)}</p>{!finite.includes(selectedAction) && <p className="blocker" role="status">{t('table.invalidatedChoice')}</p>}<button disabled={busy || !finite.includes(selectedAction)} onClick={async () => { if (await submit(selectedAction)) setSelectedAction(''); }}>{t('table.confirm')}</button></div>}
        {snapshot.game.shippingOffer && <div className="purchase-preview">
            <p>{t('game.ships')}: {snapshot.game.shippingOffer.ships ? `${label(snapshot.game.shippingOffer.ships.fleet, 'fleet')} ${snapshot.game.shippingOffer.ships.level}` : '—'}</p>
            <p>{t('controls.purchaseCost')}: ${snapshot.game.shippingOffer.cost} · {t('controls.remainingCash')}: ${snapshot.game.shippingOffer.cashAfter ?? '—'}</p>
            {snapshot.game.shippingOffer.blockers.map(blocker => <p key={blocker} className="blocker">{t(`error.${blocker}`)}</p>)}
            <button disabled={busy || !commands.includes('upgrade-ships') || snapshot.game.shippingOffer.blockers.length > 0} onClick={() => { void submit('upgrade-ships'); }}>{t('action.upgrade-ships')}</button>
        </div>}
        {commands.some(command => command === 'relieve-misery' || command.startsWith('relieve-misery ')) && <form className="guided-form" onSubmit={event => { event.preventDefault(); if (!busy && snapshot.game.reliefOffers.some(offer => String(offer.spaces) === relief && offer.affordable)) void submit(`relieve-misery ${relief}`); }}>
            <label htmlFor="relief-spaces">{t('action.relieveMisery')}</label><div className="inline-form"><select id="relief-spaces" value={relief} onChange={event => setRelief(event.target.value)} required>
                <option value="">{t('controls.chooseRelief')}</option>{snapshot.game.reliefOffers.map(offer => <option key={offer.spaces} value={offer.spaces} disabled={!offer.affordable}>{offer.spaces} {t('controls.spaces')} · ${offer.cashCost} · {offer.ratingSpent} {t('controls.mrUsed')}</option>)}
            </select><button disabled={busy || !snapshot.game.reliefOffers.some(offer => String(offer.spaces) === relief && offer.affordable)}>{t('action.relieveMisery')}</button></div>
            {snapshot.game.reliefOffers.length === 0 && <p className="small muted">{t('controls.noRelief')}</p>}
        </form>}
        {commands.includes('forgo-marker') && <EventMarkerPlacement snapshot={snapshot} busy={busy} submit={submit}/>}
        {commands.some(command => command.startsWith('cede-war')) && <WarSurrender snapshot={snapshot} busy={busy} submit={submit}/>}
    </div>;
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
            <div className="inline-form"><select id="marker-source" value={source} onChange={event => setSource(event.target.value)} required>
                <option value="">{t('controls.chooseProvince')}</option>{sources.map(command => <option key={command} value={command}>{provinceName(command.split(' ')[1])}</option>)}
            </select><button disabled={busy || !sources.includes(source)}>{t('action.move-marker')}</button></div>
            <MapPicker title="Choose the marker to move" eligible={sources.map(command => command.split(' ')[1])}
                selected={source ? [source.split(' ')[1]] : []} onPick={id => setSource(`move-marker ${id}`)} disabled={busy}/>
        </form>}
    </div>;
}

function WarSurrender({snapshot, busy, submit}: ActionProps) {
    const [selected, setSelected] = useState<string[]>([]);
    const cession = snapshot.game.warCession;
    const required = cession?.requiredCount ?? 0;
    const provinces = snapshot.game.catalog?.provinces.filter(province => cession?.eligibleProvinces.includes(province.id)) ?? [];
    const canSelect = (province: ProvinceDefinition, choices: string[]) => choices.includes(province.id) ||
        (choices.length < required && (!province.overseas || provinces.filter(candidate => candidate.overseas && choices.includes(candidate.id)).length < (cession?.overseasCapacity ?? 0)));
    const winner = snapshot.game.players.find(player => player.id === cession?.winner);
    const invalidSelection = !!cession && (selected.some(id => !cession.eligibleProvinces.includes(id)) || selected.length > required ||
        provinces.filter(province => province.overseas && selected.includes(province.id)).length > cession.overseasCapacity);
    const validSelection = !!cession && selected.length === required && selected.every(id => cession.eligibleProvinces.includes(id)) &&
        provinces.filter(province => province.overseas && selected.includes(province.id)).length <= cession.overseasCapacity;
    return <form className="guided-form" onSubmit={event => { event.preventDefault(); if (!busy && validSelection) void submit(`cede-war ${selected.join(' ')}`.trim()); }}>
        <h3>{t('action.cedeWar')} · {required}</h3><p>{t('controls.warWinner')}: {winner?.name}. {t('controls.overseasCapacity')}: {cession?.overseasCapacity}.</p>
        <div className="province-checks">{provinces.map(province => <label className="check-label" key={province.id}><input type="checkbox" checked={selected.includes(province.id)} disabled={busy || !canSelect(province, selected)} onChange={event => setSelected(current => event.target.checked ? [...current, province.id] : current.filter(id => id !== province.id))}/>{provinceName(province.id)}</label>)}</div>
        <MapPicker title={`Choose ${required} provinces to cede`} selected={selected} disabled={busy}
            eligible={provinces.filter(province => canSelect(province, selected)).map(province => province.id)}
            onPick={id => setSelected(current => {
                if (current.includes(id)) return current.filter(value => value !== id);
                const province = provinces.find(province => province.id === id);
                return province && canSelect(province, current) ? [...current, id] : current;
            })}/>
        {invalidSelection && <><p className="blocker" role="status">{t('table.invalidatedChoice')}</p><button type="button" className="secondary clear-targets" onClick={() => setSelected([])}>{t('table.clearSelection')}</button></>}
        <button disabled={busy || !validSelection}>{t('action.cedeWar')}</button>
    </form>;
}
