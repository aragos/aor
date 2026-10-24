import {useState} from 'react';
import type {Snapshot} from './api';
import {advanceName, label, provinceName} from './labels';
import {t} from './messages';

interface ActionProps {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>}

const simpleActions = ['pass', 'done', 'buy-card', 'upgrade-ships', 'end-expansion', 'buy-expansion-card', 'resolve-war', 'windmill', 'cathedral', 'roll', 'stabilize cash', 'stabilize misery'];

export function GameActions({snapshot, busy, submit}: ActionProps) {
    const [advance, setAdvance] = useState('');
    const [relief, setRelief] = useState('');
    const commands = snapshot.legalChoices.commands;
    const nameFor = (id: string) => snapshot.game.players.find(player => player.id === id)?.name ?? id;
    const offers = commands.filter(command => command.startsWith('buy-advance ')).map(command => {
        const definition = snapshot.game.catalog?.advances.find(candidate => candidate.messageId.endsWith(`.${command.split(' ')[1]}`));
        const offer = snapshot.game.advanceOffers.find(candidate => candidate.advance === definition?.id);
        return {command, definition, offer};
    });
    const currentOffer = offers.find(offer => offer.command === advance);
    const finite = commands.filter(command => !simpleActions.includes(command) &&
        (command.startsWith('swap-order ') || command.startsWith('cancel-adjustment ') ||
            command.startsWith('discard-card ') || command.startsWith('civil-war-loss ') ||
            command.startsWith('replace-event-token ') || command.startsWith('cathedral ') || command.startsWith('patronize-leader ') || command.startsWith('offer-card ') || command.startsWith('reduce-marker ')));
    const finiteLabel = (command: string) => {
        const [action, first, second] = command.split(' ');
        if (action === 'swap-order') return `${t('action.swapOrder')} ${nameFor(first)}`;
        if (action === 'cancel-adjustment') return `${t('action.cancel')} ${label(first.toUpperCase(), 'commodity')} ${label(second.toUpperCase(), 'adjustment')}`;
        if (action === 'discard-card') {
            const card = snapshot.game.hand.find(card => card.reference === first);
            return `${t('action.discard')} ${card ? t(card.definition.messageId) : first}`;
        }
        if (action === 'patronize-leader') return `${t('action.patronize')} ${label(first, 'leader')}`;
        if (action === 'reduce-marker') return `${t('action.reduceMarker')} ${provinceName(first)}`;
        if (action === 'offer-card') {
            const card = snapshot.game.hand.find(card => card.reference === first);
            return `${t('action.offerCard')} ${card ? t(card.definition.messageId) : first}`;
        }
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
        {commands.some(command => command === 'relieve-misery' || command.startsWith('relieve-misery ')) && <form className="guided-form" onSubmit={event => { event.preventDefault(); void submit(`relieve-misery ${relief}`); }}>
            <label htmlFor="relief-spaces">{t('action.relieveMisery')}</label><div className="inline-form"><select id="relief-spaces" value={relief} onChange={event => setRelief(event.target.value)} required>
                <option value="">{t('controls.chooseRelief')}</option>{snapshot.game.reliefOffers.map(offer => <option key={offer.spaces} value={offer.spaces} disabled={!offer.affordable}>{offer.spaces} {t('controls.spaces')} · ${offer.cashCost} · {offer.ratingSpent} {t('controls.mrUsed')}</option>)}
            </select><button disabled={busy || !snapshot.game.reliefOffers.some(offer => String(offer.spaces) === relief && offer.affordable)}>{t('action.relieveMisery')}</button></div>
            {snapshot.game.reliefOffers.length === 0 && <p className="small muted">{t('controls.noRelief')}</p>}
        </form>}
        {commands.some(command => command === 'play-card' || command.startsWith('play-card ')) && <PlayCard snapshot={snapshot} busy={busy} submit={submit}/>}
        {commands.some(command => command === 'place' || command === 'attack' || command.startsWith('place ') || command.startsWith('attack ')) && <ExpansionActions snapshot={snapshot} busy={busy} submit={submit}/>}
        {commands.some(command => command.startsWith('cede-war')) && <WarSurrender snapshot={snapshot} busy={busy} submit={submit}/>}
    </div>;
}

function PlayCard({snapshot, busy, submit}: ActionProps) {
    const [reference, setReference] = useState('');
    const [choice, setChoice] = useState('');
    const [protection, setProtection] = useState('0');
    const card = snapshot.game.hand.find(candidate => candidate.reference === reference);
    const id = card?.definition.id ?? '';
    const playerTarget = ['alchemists-gold', 'civil-war', 'war'].includes(id);
    const provinceTarget = ['pirates-vikings', 'rebellion', 'the-crusades'].includes(id);
    const dual = id === 'cloth-wine' ? ['CLOTH', 'WINE'] : id === 'ivory-gold' ? ['IVORY', 'GOLD'] : [];
    const category = id === 'papal-decree';
    const protectedLeader = card?.definition.kind === 'leader' && snapshot.game.effects.leaders.length > 0;
    const noArgumentAllowed = snapshot.legalChoices.commands.includes(`play-card ${reference}`);
    const needsChoice = !noArgumentAllowed && (playerTarget || provinceTarget || dual.length > 0 || category);
    let options: {value: string; label: string}[] = [];
    if (playerTarget) options = snapshot.game.players.filter(player => player.misery !== null && (id !== 'war' || player.id !== snapshot.viewerId)).map(player => ({value: player.id, label: player.name}));
    else if (provinceTarget) options = snapshot.game.catalog?.provinces.filter(province => {
        if (id === 'the-crusades') return province.area === 'VI' && !province.satellite;
        const pieces = snapshot.game.board.find(pieces => pieces.id === province.id);
        if (!pieces?.marker || pieces.marker.side !== 'COLOURED') return false;
        if (id === 'pirates-vikings') return province.coastal;
        const capital = snapshot.game.players.find(player => player.id === snapshot.viewerId)?.capital;
        return province.overseas !== 'NEW_WORLD' && (!province.capital || province.capital === capital);
    }).map(province => ({value: province.id, label: provinceName(province.id)})) ?? [];
    else if (dual.length) options = dual.map(commodity => ({value: commodity.toLowerCase(), label: label(commodity, 'commodity')}));
    else if (category) options = ['none', 'science', 'religion', 'exploration'].map(value => ({value, label: label(value.toUpperCase(), 'category')}));
    const choicePrefix = playerTarget ? 'player' : provinceTarget ? 'province' : category ? 'category' : 'commodity';
    const cash = Number(protection);
    const validProtection = !protectedLeader || (protection.trim() !== '' && Number.isInteger(cash) && cash >= 0 && cash <= 2147483647);
    const playable = snapshot.legalChoices.commands.includes('play-card') ? snapshot.game.hand : snapshot.game.hand.filter(card => snapshot.legalChoices.commands.some(command => command.split(' ')[0] === 'play-card' && command.split(' ')[1] === card.reference));
    const concreteChoices = snapshot.legalChoices.commands.filter(command => command.startsWith(`play-card ${reference} `));
    if (concreteChoices.length > 0 && needsChoice) options = options.filter(option => concreteChoices.some(command => command.endsWith(` ${choicePrefix} ${option.value}`)));
    return <form className="guided-form play-card-form" onSubmit={async event => {
        event.preventDefault();
        const argument = needsChoice ? ` ${choicePrefix} ${choice}` : protectedLeader ? ` protect ${cash}` : '';
        if (await submit(`play-card ${reference}${argument}`)) { setReference(''); setChoice(''); setProtection('0'); }
    }}>
        <label htmlFor="play-card">{t('action.playCard')}</label><select id="play-card" value={reference} onChange={event => { setReference(event.target.value); setChoice(''); }} required>
            <option value="">{t('controls.chooseCard')}</option>{playable.map(card => <option key={card.reference} value={card.reference} disabled={snapshot.game.burdenReferences.includes(card.reference)}>{t(card.definition.messageId)} · {card.reference}{snapshot.game.burdenReferences.includes(card.reference) ? ` · ${t('game.burden')}` : ''}</option>)}
        </select>
        {needsChoice && <><label htmlFor="card-choice">{t('controls.cardChoice')}</label><select id="card-choice" value={choice} onChange={event => setChoice(event.target.value)} required><option value="">{t('controls.chooseTarget')}</option>{options.map(option => <option key={option.value} value={option.value}>{option.label}</option>)}</select></>}
        {protectedLeader && <><label htmlFor="leader-protection">{t('game.protection')}</label><input id="leader-protection" type="number" min={0} max={2147483647} step={1} value={protection} onChange={event => setProtection(event.target.value)}/></>}
        <button disabled={busy || !card || (needsChoice && !choice) || !validProtection || snapshot.game.burdenReferences.includes(reference)}>{t('action.playCard')}</button>
    </form>;
}

function ExpansionActions({snapshot, busy, submit}: ActionProps) {
    const [operation, setOperation] = useState('place');
    const [province, setProvince] = useState('');
    const [tokens, setTokens] = useState('1');
    const [route, setRoute] = useState('auto');
    const [via, setVia] = useState('');
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId)!;
    const provinces = snapshot.game.catalog?.provinces ?? [];
    const count = Number(tokens);
    return <form className="guided-form expansion-form" onSubmit={event => {
        event.preventDefault();
        const routeArgument = route === 'auto' ? '' : route === 'caravan' ? ` caravan ${via}` : ` ${route}`;
        void submit(`${operation} ${operation === 'place' ? `${count} ` : ''}${province}${routeArgument}`);
    }}>
        <h3>{t('action.expand')}</h3>
        <div className="action-fields"><label>{t('controls.expansionAction')}<select value={operation} onChange={event => setOperation(event.target.value)}><option value="place">{t('action.place')}</option><option value="attack">{t('action.attack')}</option></select></label>
            <label>{t('board.province')}<select value={province} onChange={event => setProvince(event.target.value)} required><option value="">{t('controls.chooseProvince')}</option>{provinces.map(province => <option key={province.id} value={province.id}>{provinceName(province.id)} · {province.marketNumber}</option>)}</select></label>
            {operation === 'place' && <label>{t('controls.tokenCount')}<input type="number" min={1} max={me.poolTokens} step={1} value={tokens} onChange={event => setTokens(event.target.value)} required/></label>}
            <label>{t('controls.route')}<select value={route} onChange={event => setRoute(event.target.value)}>{['auto', 'land', 'ship', 'caravan'].map(route => <option key={route} value={route}>{label(route, 'route')}</option>)}</select></label>
            {route === 'caravan' && <label>{t('controls.via')}<select value={via} onChange={event => setVia(event.target.value)} required><option value="">{t('controls.chooseProvince')}</option>{provinces.map(province => <option key={province.id} value={province.id}>{provinceName(province.id)}</option>)}</select></label>}
        </div>
        {operation === 'attack' && <p className="muted small">{t('controls.attackHint')}</p>}
        <button disabled={busy || !province || (route === 'caravan' && !via) || (operation === 'place' && (!Number.isInteger(count) || count < 1 || count > me.poolTokens))}>{t(operation === 'place' ? 'action.place' : 'action.attack')}</button>
    </form>;
}

function WarSurrender({snapshot, busy, submit}: ActionProps) {
    const [selected, setSelected] = useState<string[]>([]);
    const cession = snapshot.game.warCession;
    const required = cession?.requiredCount ?? 0;
    const provinces = snapshot.game.catalog?.provinces.filter(province => cession?.eligibleProvinces.includes(province.id)) ?? [];
    const overseasCount = provinces.filter(province => province.overseas && selected.includes(province.id)).length;
    const winner = snapshot.game.players.find(player => player.id === cession?.winner);
    return <form className="guided-form" onSubmit={event => { event.preventDefault(); void submit(`cede-war ${selected.join(' ')}`.trim()); }}>
        <h3>{t('action.cedeWar')} · {required}</h3><p className="small muted">{t('controls.cedeWarHint')}</p><p>{t('controls.warWinner')}: {winner?.name}. {t('controls.overseasCapacity')}: {cession?.overseasCapacity}.</p>
        <div className="province-checks">{provinces.map(province => <label className="check-label" key={province.id}><input type="checkbox" checked={selected.includes(province.id)} disabled={busy || (!selected.includes(province.id) && (selected.length >= required || (!!province.overseas && overseasCount >= (cession?.overseasCapacity ?? 0))))} onChange={event => setSelected(current => event.target.checked ? [...current, province.id] : current.filter(id => id !== province.id))}/>{provinceName(province.id)}</label>)}</div>
        <button disabled={busy || !cession || selected.length !== required}>{t('action.cedeWar')}</button>
    </form>;
}
