import {useEffect, useState} from 'react';
import type {CardChoiceView, Snapshot} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';
import {CardEventText} from './CardEventText';
import {Commodity, CommodityAdjustments, Emblem, Payouts, PayoutReference, UnplayedLeaders} from './TablePanels';
import {MapPicker, PlayerPicker, useMapSelection} from './map/MapSelection';
import {InvalidChoice, useTargets} from './DecisionControls';
import {AdvanceBenefits} from './AdvanceBenefits';

interface Props {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>;
    mode?: 'inspection' | 'action' | 'all';
}

function choiceLabel(snapshot: Snapshot, choice: CardChoiceView): string {
    if (choice.kind === 'PLAYER') return playerName(snapshot, choice.value ?? '');
    if (choice.kind === 'PROVINCE') return provinceName(choice.value ?? '');
    if (choice.kind === 'COMMODITY') return label(choice.value?.toUpperCase() ?? '', 'commodity');
    if (choice.kind === 'CATEGORY') return choice.value ? label(choice.value.toUpperCase(), 'category') : t('table.noCategory');
    if (choice.kind === 'AREA') return `${t('board.area')} ${choice.value?.toUpperCase()}`;
    return t('action.playCard');
}

export function CardDecision({snapshot, busy, submit, mode = 'all'}: Props) {
    const {selectedCard, setSelectedCard} = useMapSelection();
    const [choice, setChoice] = useState('');
    const [protection, setProtection] = useState('0');
    useEffect(() => { setChoice(''); setProtection('0'); }, [selectedCard]);
    const card = snapshot.game.hand.find(candidate => candidate.reference === selectedCard);
    const offer = snapshot.game.cardOffers.find(candidate => candidate.reference === selectedCard);
    const selection = useTargets(offer?.provinceTargets ?? [], offer?.requiredTargetCount ?? 0);
    const targets = selection.selected;
    if (!card) return null;
    const finiteChoices = offer?.choices.filter(candidate => candidate.command !== null) ?? [];
    const needsTargets = offer?.choices.some(candidate => candidate.kind === 'PROVINCES') ?? false;
    const needsProtection = offer?.choices.some(candidate => candidate.kind === 'PROTECTION') ?? false;
    const amount = Number(protection);
    const validProtection = protection.trim() !== '' && Number.isInteger(amount) && amount >= 0 && offer?.protectionMax !== null && amount <= (offer?.protectionMax ?? -1);
    const selectedChoice = finiteChoices.find(candidate => candidate.command === choice);
    const noChoice = !needsTargets && finiteChoices.length === 1 && ['NONE', 'COMMODITY'].includes(finiteChoices[0].kind);
    const invalidTargets = needsTargets && selection.invalid;
    const validTargets = needsTargets && selection.valid;
    const protectedPlay = validProtection
        ? amount === 0 ? finiteChoices.find(candidate => candidate.kind === 'NONE')?.command : `play ${selectedCard} protect ${amount}`
        : null;
    const command = needsTargets && validTargets ? `play ${selectedCard} provinces ${[...targets].sort().join(' ')}`
        : needsProtection ? protectedPlay
        : noChoice ? finiteChoices[0].command : selectedChoice?.command;
    const actionable = !!offer?.playable && !!command;
    const provinceChoices = finiteChoices.filter(candidate => candidate.kind === 'PROVINCE');
    const areaChoices = finiteChoices.filter(candidate => candidate.kind === 'AREA');
    const inspection = mode !== 'action';
    const action = mode !== 'inspection';
    return <section className="object-decision card-decision">
        {inspection && <button type="button" className="close-detail" aria-label={t('table.close')} onClick={() => setSelectedCard('')}>×</button>}
        {inspection && <p className="eyebrow">{t(`kind.${card.definition.kind}`)}</p>}<h2>{t(card.definition.messageId)}</h2>
        {inspection && <><CardEventText definition={card.definition}/>
        {offer?.commodities.map(commodity => {
            const payout = snapshot.game.commodityPayouts.find(candidate => candidate.commodity === commodity);
            return <div className="card-value" key={commodity}><Commodity commodity={commodity}/>{payout && <>
                <CommodityAdjustments payout={payout}/><Payouts snapshot={snapshot} payout={payout}/>
                <PayoutReference snapshot={snapshot} payout={payout}/>
            </>}</div>;
        })}
        {offer?.leaderDiscounts.map(discount => <details key={discount.advance} className="leader-discount-detail">
            <summary>{advanceName(discount.advance)} <strong>−${discount.effectiveDiscount}</strong></summary>
            <dl className="detail-list"><div><dt>{t('table.printedDiscount')}</dt><dd>${discount.printedAmount}</dd></div>
                <div><dt>{advanceName('written-record')}</dt><dd>${discount.writtenRecordBonus}</dd></div>
                <div><dt>{t('table.conditionalDiscount')}</dt><dd>${discount.conditionalAmount}</dd></div></dl>
            {discount.condition && <p>{label(discount.condition)}</p>}
        </details>)}</>}
        {action && offer?.blockers.map(blocker => <p className="blocker" key={blocker}>{t(`error.${blocker}`)}</p>)}
        {action && offer?.playable && <form onSubmit={async event => { event.preventDefault(); if (actionable && command) await submit(command); }}>
            {!noChoice && finiteChoices.length > 0 && <fieldset className="target-choices"><legend>{t('controls.cardChoice')}</legend>
                {finiteChoices.map(candidate => <button type="button" key={candidate.command} className="secondary"
                    aria-pressed={candidate.command === choice} onClick={() => { setChoice(candidate.command ?? ''); setProtection(''); }}>
                    {candidate.kind === 'PLAYER' && snapshot.game.players.filter(player => player.id === candidate.value).map(player => <Emblem player={player} key={player.id}/>)}
                    {choiceLabel(snapshot, candidate)}</button>)}
            </fieldset>}
            {finiteChoices.some(candidate => candidate.kind === 'PLAYER') && <PlayerPicker disabled={busy}
                eligible={finiteChoices.filter(candidate => candidate.kind === 'PLAYER').flatMap(candidate => candidate.value ? [candidate.value] : [])}
                selected={selectedChoice?.value ? [selectedChoice.value] : []}
                onPick={id => setChoice(finiteChoices.find(candidate => candidate.kind === 'PLAYER' && candidate.value === id)?.command ?? '')}/>}
            {provinceChoices.length > 0 && <MapPicker title={t('controls.cardChoice')} disabled={busy} eligible={provinceChoices.flatMap(candidate => candidate.value ? [candidate.value] : [])}
                selected={selectedChoice?.value ? [selectedChoice.value] : []} onPick={id => setChoice(provinceChoices.find(candidate => candidate.value === id)?.command ?? '')}/>}
            {areaChoices.length > 0 && <MapPicker kind="area" title={t('controls.cardChoice')} disabled={busy}
                eligible={areaChoices.flatMap(candidate => candidate.value ? [candidate.value.toUpperCase()] : [])}
                selected={selectedChoice?.kind === 'AREA' && selectedChoice.value ? [selectedChoice.value.toUpperCase()] : []}
                onPick={area => setChoice(areaChoices.find(candidate => candidate.value?.toUpperCase() === area)?.command ?? '')}/>}
            {needsTargets && offer && <fieldset className="target-choices"><legend>{t('table.targets')} · {targets.length}/{offer.requiredTargetCount}</legend>
                {offer.provinceTargets.map(province => <button type="button" key={province} className="secondary" aria-pressed={targets.includes(province)}
                    disabled={busy || !selection.canPick(province)}
                    onClick={() => selection.pick(province)}>{provinceName(province)}</button>)}
                <MapPicker title={t('table.targets')} disabled={busy} selected={targets}
                    eligible={offer.provinceTargets.filter(target => selection.canPick(target))}
                    onPick={selection.pick}/>
            </fieldset>}
            {needsProtection && <label>{t('game.protection')}<input type="number" min={0} max={offer?.protectionMax ?? 0} value={protection}
                onChange={event => {setProtection(event.target.value); setChoice('');}}/></label>}
            {needsProtection && protection.trim() !== '' && !validProtection && <p className="blocker">{t('table.invalidProtection').replace('{max}', String(offer?.protectionMax))}</p>}
            <InvalidChoice invalid={!!(choice && !selectedChoice) || invalidTargets} clear={() => {setChoice(''); selection.clear();}}/>
            <button disabled={busy || !actionable}>{t('action.playCard')}</button>
        </form>}
    </section>;
}

export function AdvanceDecision({snapshot, busy, submit, mode = 'all'}: Props) {
    const {selectedAdvance, setSelectedAdvance} = useMapSelection();
    const advance = snapshot.game.catalog?.advances.find(candidate => candidate.id === selectedAdvance);
    if (!advance) return null;
    const offer = snapshot.game.advanceOffers.find(candidate => candidate.advance === advance.id);
    const command = `buy-advance ${advance.letter}`;
    const available = offer && offer.unavailableReason === null && snapshot.legalChoices.commands.includes(command);
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const inspection = mode !== 'action';
    const action = mode !== 'inspection';
    return <section className="object-decision advance-decision">
        {inspection && <button type="button" className="close-detail" aria-label={t('table.close')} onClick={() => setSelectedAdvance('')}>×</button>}
        {inspection && <p className="eyebrow">{label(advance.category, 'category')}</p>}<h2>{advanceName(advance.id)}</h2>
        {inspection && <><AdvanceBenefits id={advance.id} snapshot={snapshot}/>
            <UnplayedLeaders snapshot={snapshot} advance={advance.id}/></>}
        {offer?.price !== null && offer?.price !== undefined && <div className="price-quote"><strong>${offer.price}</strong><span>{t('controls.remainingCash')}: ${(me?.cash ?? 0) - offer.price}</span></div>}
        {action && offer?.unavailableReason && <p className="blocker">{t(`error.${offer.unavailableReason}`)}</p>}
        {offer?.leaderDiscounts?.map(discount => <p className="leader-discount" key={discount.leaderCardId}>
            {label(discount.leaderCardId, 'leader')} <strong>−${discount.amount}</strong>
        </p>)}
        {inspection && <dl className="detail-list"><div><dt>{t('table.printedCost')}</dt><dd>${advance.cost}</dd></div><div><dt>{t('advance.prerequisites')}</dt><dd>{advance.prerequisites.map(advanceName).join(', ') || '—'}</dd></div>
            <div><dt>{t('advance.credit')}</dt><dd>${advance.credit}</dd></div><div><dt>{t('advance.mr')}</dt><dd>{advance.miseryRelief}</dd></div>
            <div><dt>{t('table.purchaseMisery')}</dt><dd>{advance.purchaseMisery}</dd></div>
            <div><dt>{t('advance.owners')}</dt><dd>{snapshot.game.players.filter(player => player.advances.includes(advance.id)).map(player => playerName(snapshot, player.id)).join(', ') || '—'}</dd></div></dl>}
        {action && offer && <button disabled={busy || !available} onClick={async () => { if (available && await submit(command)) setSelectedAdvance(''); }}>{t('action.buyAdvance')}</button>}
    </section>;
}
