import {useState} from 'react';
import type {FormEvent} from 'react';
import type {ProvinceDefinition, Snapshot} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';

interface ActionProps {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>}

const simpleActions = ['pass', 'buy-card', 'upgrade-ships', 'buy-expansion-card', 'windmill', 'cathedral', 'stabilize cash', 'stabilize misery'];

export function GameActions({snapshot, busy, submit}: ActionProps) {
    const [advance, setAdvance] = useState('');
    const [relief, setRelief] = useState('');
    const commands = snapshot.legalChoices.commands;
    const offers = commands.filter(command => command.startsWith('buy-advance ')).map(command => {
        const definition = snapshot.game.catalog?.advances.find(candidate => candidate.messageId.endsWith(`.${command.split(' ')[1]}`));
        const offer = snapshot.game.advanceOffers.find(candidate => candidate.advance === definition?.id);
        return {command, definition, offer};
    });
    const currentOffer = offers.find(offer => offer.command === advance);
    const finite = commands.filter(command => !simpleActions.includes(command) &&
        (command.startsWith('swap-order ') || command.startsWith('cancel-adjustment ') ||
            command.startsWith('discard-card ') || command.startsWith('civil-war-loss ') ||
            command.startsWith('replace-event-token ') || command.startsWith('cathedral ') || command.startsWith('patronize ') || command.startsWith('offer-card ') || command.startsWith('reduce-marker ')));
    const finiteLabel = (command: string) => {
        const [action, first, second] = command.split(' ');
        if (action === 'swap-order') return `${t('action.swapOrder')} ${playerName(snapshot, first)}`;
        if (action === 'cancel-adjustment') return `${t('action.cancel')} ${label(first.toUpperCase(), 'commodity')} ${label(second.toUpperCase(), 'adjustment')}`;
        if (action === 'discard-card' || action === 'offer-card') {
            const card = snapshot.game.hand.find(card => card.reference === first);
            const actionKey = action === 'discard-card' ? 'action.discard' : 'action.offerCard';
            return `${t(actionKey)} ${card ? t(card.definition.messageId) : first}`;
        }
        if (action === 'patronize') return `${t('action.patronize')} ${label(first, 'leader')}`;
        if (action === 'reduce-marker') return `${t('action.reduceMarker')} ${provinceName(first)}`;
        return `${label(action, 'action')} ${label(first)}`;
    };
    return <div className="game-actions">
        {snapshot.game.activity?.purchaseStep === 'STABILIZATION' && <p>{t('controls.stabilizationCost')}: <strong>${snapshot.game.stabilizationCost}</strong></p>}
        <div className="action-buttons">{commands.filter(command => simpleActions.includes(command)).map(command => <button key={command} disabled={busy} onClick={() => { void submit(command); }}>{label(command, 'action')}</button>)}
            {finite.map(command => <button className="secondary" key={command} disabled={busy} onClick={() => { void submit(command); }}>{finiteLabel(command)}</button>)}
        </div>
        {offers.length > 0 && <form className="guided-form" onSubmit={event => { event.preventDefault(); void submit(advance); }}>
            <label htmlFor="buy-advance">{t('action.buyAdvance')}</label>
            <div className="inline-form"><select id="buy-advance" value={advance} onChange={event => setAdvance(event.target.value)} required>
                <option value="">{t('controls.chooseAdvance')}</option>{offers.map(({command, definition, offer}) => <option key={command} value={command} disabled={!offer || offer.unavailableReason !== null}>
                    {definition ? advanceName(definition.id) : command} {offer?.price !== null && offer?.price !== undefined ? `· $${offer.price}` : ''}{offer?.unavailableReason ? ` · ${t(`error.${offer.unavailableReason}`)}` : ''}
                </option>)}
            </select><button disabled={busy || !currentOffer?.offer || currentOffer.offer.unavailableReason !== null}>{t('action.buyAdvance')}</button></div>
        </form>}
        {commands.includes('relieve-misery') && <form className="guided-form" onSubmit={event => { event.preventDefault(); void submit(`relieve-misery ${relief}`); }}>
            <label htmlFor="relief-spaces">{t('action.relieveMisery')}</label><div className="inline-form"><select id="relief-spaces" value={relief} onChange={event => setRelief(event.target.value)} required>
                <option value="">{t('controls.chooseRelief')}</option>{snapshot.game.reliefOffers.map(offer => <option key={offer.spaces} value={offer.spaces} disabled={!offer.affordable}>{offer.spaces} {t('controls.spaces')} · ${offer.cashCost} · {offer.ratingSpent} {t('controls.mrUsed')}</option>)}
            </select><button disabled={busy || !snapshot.game.reliefOffers.some(offer => String(offer.spaces) === relief && offer.affordable)}>{t('action.relieveMisery')}</button></div>
            {snapshot.game.reliefOffers.length === 0 && <p className="small muted">{t('controls.noRelief')}</p>}
        </form>}
        {commands.some(command => command === 'play' || command.startsWith('play ')) && <PlayCard snapshot={snapshot} busy={busy} submit={submit}/>}
        {commands.some(command => command === 'place' || command === 'attack' || command.startsWith('place ') || command.startsWith('attack ')) && <ExpansionActions snapshot={snapshot} busy={busy} submit={submit}/>}
        {commands.some(command => command.startsWith('cede-war')) && <WarSurrender snapshot={snapshot} busy={busy} submit={submit}/>}
    </div>;
}

interface CardTarget {
    prefix: 'player' | 'province' | 'commodity' | 'category';
    options: {value: string; label: string}[];
}

function cardTarget(snapshot: Snapshot, id: string): CardTarget | null {
    if (['alchemists-gold', 'civil-war', 'war'].includes(id)) {
        return {
            prefix: 'player',
            options: snapshot.game.players
                .filter(player => player.misery !== null && (id !== 'war' || player.id !== snapshot.viewerId))
                .map(player => ({value: player.id, label: player.name})),
        };
    }
    if (['pirates-vikings', 'rebellion', 'the-crusades'].includes(id)) {
        return {
            prefix: 'province',
            options: (snapshot.game.catalog?.provinces ?? [])
                .filter(province => isCardProvince(snapshot, id, province))
                .map(province => ({value: province.id, label: provinceName(province.id)})),
        };
    }
    const dualCommodities: Record<string, string[]> = {
        'cloth-wine': ['CLOTH', 'WINE'],
        'ivory-gold': ['IVORY', 'GOLD'],
    };
    const commodities = dualCommodities[id];
    if (commodities) {
        return {
            prefix: 'commodity',
            options: commodities.map(commodity => ({
                value: commodity.toLowerCase(),
                label: label(commodity, 'commodity'),
            })),
        };
    }
    if (id === 'papal-decree') {
        return {
            prefix: 'category',
            options: ['none', 'science', 'religion', 'exploration'].map(value => ({
                value,
                label: label(value.toUpperCase(), 'category'),
            })),
        };
    }
    return null;
}

function isCardProvince(snapshot: Snapshot, card: string, province: ProvinceDefinition): boolean {
    if (card === 'the-crusades') return province.area === 'VI' && !province.satellite;
    const pieces = snapshot.game.board.find(pieces => pieces.id === province.id);
    if (!pieces?.marker || pieces.marker.side !== 'COLOURED') return false;
    if (card === 'pirates-vikings') return province.coastal;
    const capital = snapshot.game.players.find(player => player.id === snapshot.viewerId)?.capital;
    return province.overseas !== 'NEW_WORLD' && (!province.capital || province.capital === capital);
}

function playableCards(snapshot: Snapshot) {
    const commands = snapshot.legalChoices.commands;
    if (commands.includes('play')) return snapshot.game.hand;
    const references = new Set(commands
        .filter(command => command.startsWith('play '))
        .map(command => command.split(' ')[1]));
    return snapshot.game.hand.filter(card => references.has(card.reference));
}

function PlayCard({snapshot, busy, submit}: ActionProps) {
    const [reference, setReference] = useState('');
    const [choice, setChoice] = useState('');
    const [protection, setProtection] = useState('0');
    const card = snapshot.game.hand.find(candidate => candidate.reference === reference);
    const id = card?.definition.id ?? '';
    const target = cardTarget(snapshot, id);
    const protectedLeader = card?.definition.kind === 'leader' && snapshot.game.effects.leaders.length > 0;
    const noArgumentAllowed = snapshot.legalChoices.commands.includes(`play ${reference}`);
    const needsChoice = !noArgumentAllowed && target !== null;
    const cash = Number(protection);
    const validProtection = !protectedLeader || (protection.trim() !== '' && Number.isInteger(cash) && cash >= 0 && cash <= 2147483647);
    const playable = playableCards(snapshot);
    const concreteChoices = snapshot.legalChoices.commands.filter(command => command.startsWith(`play ${reference} `));
    const targetOptions = target?.options ?? [];
    const options = concreteChoices.length > 0 && needsChoice
        ? targetOptions.filter(option => concreteChoices.some(command => command.endsWith(` ${target.prefix} ${option.value}`)))
        : targetOptions;
    async function play(event: FormEvent) {
        event.preventDefault();
        let argument = '';
        if (needsChoice) argument = ` ${target.prefix} ${choice}`;
        else if (protectedLeader) argument = ` protect ${cash}`;
        if (!await submit(`play ${reference}${argument}`)) return;
        setReference('');
        setChoice('');
        setProtection('0');
    }
    return <form className="guided-form play-card-form" onSubmit={play}>
        <label htmlFor="play-card">{t('action.playCard')}</label>
        <select id="play-card" value={reference} onChange={event => {
            setReference(event.target.value);
            setChoice('');
        }} required>
            <option value="">{t('controls.chooseCard')}</option>
            {playable.map(card => <option
                key={card.reference}
                value={card.reference}
                disabled={snapshot.game.burdenReferences.includes(card.reference)}
            >
                {t(card.definition.messageId)} · {card.reference}
                {snapshot.game.burdenReferences.includes(card.reference) && ` · ${t('game.burden')}`}
            </option>)}
        </select>
        {needsChoice && <>
            <label htmlFor="card-choice">{t('controls.cardChoice')}</label>
            <select id="card-choice" value={choice} onChange={event => setChoice(event.target.value)} required>
                <option value="">{t('controls.chooseTarget')}</option>
                {options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
        </>}
        {protectedLeader && <>
            <label htmlFor="leader-protection">{t('game.protection')}</label>
            <input id="leader-protection" type="number" min={0} max={2147483647} step={1}
                value={protection} onChange={event => setProtection(event.target.value)}/>
        </>}
        <button disabled={busy || !card || (needsChoice && !choice) || !validProtection || snapshot.game.burdenReferences.includes(reference)}>
            {t('action.playCard')}
        </button>
    </form>;
}

function ExpansionActions({snapshot, busy, submit}: ActionProps) {
    const [operation, setOperation] = useState('place');
    const [province, setProvince] = useState('');
    const [tokens, setTokens] = useState('1');
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId)!;
    const provinces = snapshot.game.catalog?.provinces ?? [];
    const count = Number(tokens);
    return <form className="guided-form expansion-form" onSubmit={event => {
        event.preventDefault();
        void submit(`${operation} ${operation === 'place' ? `${count} ` : ''}${province}`);
    }}>
        <h3>{t('action.expand')}</h3>
        <div className="action-fields"><label>{t('controls.expansionAction')}<select value={operation} onChange={event => setOperation(event.target.value)}><option value="place">{t('action.place')}</option><option value="attack">{t('action.attack')}</option></select></label>
            <label>{t('board.province')}<select value={province} onChange={event => setProvince(event.target.value)} required><option value="">{t('controls.chooseProvince')}</option>{provinces.map(province => <option key={province.id} value={province.id}>{provinceName(province.id)} · {province.marketNumber}</option>)}</select></label>
            {operation === 'place' && <label>{t('controls.tokenCount')}<input type="number" min={1} max={me.poolTokens} step={1} value={tokens} onChange={event => setTokens(event.target.value)} required/></label>}
        </div>
        <p className="muted small">{t('controls.routeHint')}</p>
        {operation === 'attack' && <p className="muted small">{t('controls.attackHint')}</p>}
        <button disabled={busy || !province || (operation === 'place' && (!Number.isInteger(count) || count < 1 || count > me.poolTokens))}>{t(operation === 'place' ? 'action.place' : 'action.attack')}</button>
    </form>;
}

function WarSurrender({snapshot, busy, submit}: ActionProps) {
    const [selected, setSelected] = useState<string[]>([]);
    const suggested = snapshot.legalChoices.commands.find(command => command.startsWith('cede-war'))!;
    const required = suggested.trim().split(/\s+/).length - 1;
    const war = snapshot.game.activeWar;
    const winnerId = war?.initiator === snapshot.viewerId ? war.opponent : war?.initiator;
    const winner = snapshot.game.players.find(player => player.id === winnerId);
    const provinces = snapshot.game.catalog?.provinces.filter(province => {
        const holding = snapshot.game.board.find(pieces => pieces.id === province.id);
        return holding?.marker?.owner === snapshot.viewerId && holding.marker.side === 'COLOURED' &&
            (province.area !== 'V' || winner?.advances.includes('OVERLAND_EAST')) &&
            (!province.overseas || winner?.advances.includes('OCEAN_NAVIGATION')) &&
            (province.overseas !== 'NEW_WORLD' || winner?.advances.includes('NEW_WORLD'));
    }) ?? [];
    return <form className="guided-form" onSubmit={event => { event.preventDefault(); void submit(`cede-war ${selected.join(' ')}`.trim()); }}>
        <h3>{t('action.cedeWar')} · {required}</h3><p className="small muted">{t('controls.cedeWarHint')}</p>
        <div className="province-checks">{provinces.map(province => <label className="check-label" key={province.id}><input type="checkbox" checked={selected.includes(province.id)} onChange={event => setSelected(current => event.target.checked ? [...current, province.id] : current.filter(id => id !== province.id))}/>{provinceName(province.id)}</label>)}</div>
        <button disabled={busy || selected.length !== required}>{t('action.cedeWar')}</button>
    </form>;
}
