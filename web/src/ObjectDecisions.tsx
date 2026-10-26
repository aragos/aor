import {useEffect, useState} from 'react';
import type {CardChoiceView, Snapshot} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';
import {Commodity, Emblem, Payouts} from './TablePanels';
import {MapPicker, useMapSelection} from './map/MapSelection';

interface Props {snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>}

function choiceLabel(snapshot: Snapshot, choice: CardChoiceView): string {
    if (choice.kind === 'PLAYER') return playerName(snapshot, choice.value ?? '');
    if (choice.kind === 'PROVINCE') return provinceName(choice.value ?? '');
    if (choice.kind === 'COMMODITY') return label(choice.value?.toUpperCase() ?? '', 'commodity');
    if (choice.kind === 'CATEGORY') return choice.value ? label(choice.value.toUpperCase(), 'category') : t('table.noCategory');
    if (choice.kind === 'AREA') return `${t('board.area')} ${choice.value?.toUpperCase()}`;
    return t('action.playCard');
}

export function CardDecision({snapshot, busy, submit}: Props) {
    const {selectedCard, setSelectedCard} = useMapSelection();
    const [choice, setChoice] = useState('');
    const [targets, setTargets] = useState<string[]>([]);
    const [protection, setProtection] = useState('');
    useEffect(() => { setChoice(''); setTargets([]); setProtection(''); }, [selectedCard]);
    const card = snapshot.game.hand.find(candidate => candidate.reference === selectedCard);
    const offer = snapshot.game.cardOffers.find(candidate => candidate.reference === selectedCard);
    if (!card) return null;
    const finiteChoices = offer?.choices.filter(candidate => candidate.command !== null) ?? [];
    const needsTargets = offer?.choices.some(candidate => candidate.kind === 'PROVINCES') ?? false;
    const needsProtection = offer?.choices.some(candidate => candidate.kind === 'PROTECTION') ?? false;
    const amount = Number(protection);
    const validProtection = protection.trim() !== '' && Number.isInteger(amount) && amount >= 0 && offer?.protectionMax !== null && amount <= (offer?.protectionMax ?? -1);
    const selectedChoice = finiteChoices.find(candidate => candidate.command === choice);
    const noChoice = !needsProtection && finiteChoices.length === 1 && finiteChoices[0].kind === 'NONE';
    const invalidTargets = !!offer && needsTargets && (targets.some(target => !offer.provinceTargets.includes(target)) || targets.length > (offer.requiredTargetCount ?? 0));
    const validTargets = needsTargets && targets.length === offer?.requiredTargetCount && targets.every(target => offer.provinceTargets.includes(target));
    const command = needsTargets && validTargets ? `play ${selectedCard} provinces ${[...targets].sort().join(' ')}`
        : needsProtection && validProtection ? `play ${selectedCard} protect ${amount}`
        : noChoice ? finiteChoices[0].command : selectedChoice?.command;
    const actionable = !!offer?.playable && !!command;
    const provinceChoices = finiteChoices.filter(candidate => candidate.kind === 'PROVINCE');
    const areaChoices = finiteChoices.filter(candidate => candidate.kind === 'AREA');
    return <section className="object-decision card-decision">
        <button type="button" className="close-detail" aria-label={t('table.close')} onClick={() => setSelectedCard('')}>×</button>
        <p className="eyebrow">{t(`kind.${card.definition.kind}`)}</p><h2>{t(card.definition.messageId)}</h2>
        {offer?.commodities.map(commodity => {
            const payout = snapshot.game.commodityPayouts.find(candidate => candidate.commodity === commodity);
            return <div className="card-value" key={commodity}><Commodity commodity={commodity}/>{payout && <Payouts snapshot={snapshot} payout={payout}/>}</div>;
        })}
        {offer?.leaderDiscounts.map(discount => <details key={discount.advance} className="leader-discount-detail">
            <summary>{advanceName(discount.advance)} <strong>−${discount.effectiveDiscount}</strong></summary>
            <dl className="detail-list"><div><dt>{t('table.printedDiscount')}</dt><dd>${discount.printedAmount}</dd></div>
                <div><dt>{advanceName('written-record')}</dt><dd>${discount.writtenRecordBonus}</dd></div>
                <div><dt>{t('table.conditionalDiscount')}</dt><dd>${discount.conditionalAmount}</dd></div></dl>
            {discount.condition && <p>{label(discount.condition)}</p>}
        </details>)}
        {offer?.blockers.map(blocker => <p className="blocker" key={blocker}>{t(`error.${blocker}`)}</p>)}
        {offer?.playable && <form onSubmit={async event => { event.preventDefault(); if (actionable && command && await submit(command)) setSelectedCard(''); }}>
            {!noChoice && finiteChoices.length > 0 && <fieldset className="target-choices"><legend>{t('controls.cardChoice')}</legend>
                {finiteChoices.map(candidate => <button type="button" key={candidate.command} className="secondary"
                    aria-pressed={candidate.command === choice} onClick={() => { setChoice(candidate.command ?? ''); setProtection(''); }}>
                    {candidate.kind === 'PLAYER' && snapshot.game.players.filter(player => player.id === candidate.value).map(player => <Emblem player={player} key={player.id}/>)}
                    {choiceLabel(snapshot, candidate)}</button>)}
            </fieldset>}
            {provinceChoices.length > 0 && <MapPicker title={t('controls.cardChoice')} disabled={busy} eligible={provinceChoices.flatMap(candidate => candidate.value ? [candidate.value] : [])}
                selected={selectedChoice?.value ? [selectedChoice.value] : []} onPick={id => setChoice(provinceChoices.find(candidate => candidate.value === id)?.command ?? '')}/>}
            {areaChoices.length > 0 && <MapPicker title={t('controls.cardChoice')} disabled={busy}
                eligible={(snapshot.game.catalog?.provinces ?? []).filter(province => areaChoices.some(candidate => candidate.value === province.area?.toLowerCase())).map(province => province.id)}
                selected={(snapshot.game.catalog?.provinces ?? []).filter(province => province.area?.toLowerCase() === selectedChoice?.value).map(province => province.id)}
                onPick={id => {
                    const area = snapshot.game.catalog?.provinces.find(province => province.id === id)?.area?.toLowerCase();
                    setChoice(areaChoices.find(candidate => candidate.value === area)?.command ?? '');
                }}/>}
            {needsTargets && offer && <fieldset className="target-choices"><legend>{t('table.targets')} · {targets.length}/{offer.requiredTargetCount}</legend>
                {offer.provinceTargets.map(province => <button type="button" key={province} className="secondary" aria-pressed={targets.includes(province)}
                    disabled={busy || (!targets.includes(province) && targets.length >= (offer.requiredTargetCount ?? 0))}
                    onClick={() => setTargets(current => current.includes(province) ? current.filter(target => target !== province) : [...current, province])}>{provinceName(province)}</button>)}
                <MapPicker title={t('table.targets')} disabled={busy} selected={targets}
                    eligible={offer.provinceTargets.filter(target => targets.includes(target) || targets.length < (offer.requiredTargetCount ?? 0))}
                    onPick={id => setTargets(current => current.includes(id) ? current.filter(target => target !== id) : current.length < (offer.requiredTargetCount ?? 0) ? [...current, id] : current)}/>
            </fieldset>}
            {needsProtection && <label>{t('game.protection')}<input type="number" min={0} max={offer?.protectionMax ?? 0} value={protection}
                onChange={event => {setProtection(event.target.value); setChoice('');}}/></label>}
            {needsProtection && protection.trim() !== '' && !validProtection && <p className="blocker">{t('table.invalidProtection').replace('{max}', String(offer?.protectionMax))}</p>}
            {((choice && !selectedChoice) || invalidTargets) && <p className="blocker" role="status">{t('table.invalidatedChoice')}</p>}
            {invalidTargets && <button type="button" className="secondary clear-targets" onClick={() => setTargets([])}>{t('table.clearSelection')}</button>}
            <button disabled={busy || !actionable}>{t('action.playCard')}</button>
        </form>}
    </section>;
}

export function AdvanceDecision({snapshot, busy, submit}: Props) {
    const {selectedAdvance, setSelectedAdvance} = useMapSelection();
    const advance = snapshot.game.catalog?.advances.find(candidate => candidate.id === selectedAdvance);
    if (!advance) return null;
    const offer = snapshot.game.advanceOffers.find(candidate => candidate.advance === advance.id);
    const command = `buy-advance ${advance.letter}`;
    const available = offer && offer.unavailableReason === null && snapshot.legalChoices.commands.includes(command);
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    return <section className="object-decision advance-decision">
        <button type="button" className="close-detail" aria-label={t('table.close')} onClick={() => setSelectedAdvance('')}>×</button>
        <p className="eyebrow">{label(advance.category, 'category')}</p><h2>{advanceName(advance.id)}</h2>
        {offer?.price !== null && offer?.price !== undefined && <div className="price-quote"><strong>${offer.price}</strong><span>{t('controls.remainingCash')}: ${(me?.cash ?? 0) - offer.price}</span></div>}
        {offer?.unavailableReason && <p className="blocker">{t(`error.${offer.unavailableReason}`)}</p>}
        <dl className="detail-list"><div><dt>{t('table.printedCost')}</dt><dd>${advance.cost}</dd></div><div><dt>{t('advance.prerequisites')}</dt><dd>{advance.prerequisites.map(advanceName).join(', ') || '—'}</dd></div>
            <div><dt>{t('advance.credit')}</dt><dd>${advance.credit}</dd></div><div><dt>{t('advance.mr')}</dt><dd>{advance.miseryRelief}</dd></div>
            <div><dt>{t('table.purchaseMisery')}</dt><dd>{advance.purchaseMisery}</dd></div>
            <div><dt>{t('advance.owners')}</dt><dd>{snapshot.game.players.filter(player => player.advances.includes(advance.id)).map(player => player.name).join(', ') || '—'}</dd></div></dl>
        {offer && <button disabled={busy || !available} onClick={async () => { if (available && await submit(command)) setSelectedAdvance(''); }}>{t('action.buyAdvance')}</button>}
    </section>;
}
