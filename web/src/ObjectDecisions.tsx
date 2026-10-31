import {DecisionAction, DecisionForm} from './DecisionActions';
import {useEffect, useState} from 'react';
import type {CardChoiceView, Snapshot} from './api';
import {LabelMessage, PlayerLabel, PlayerLabels, ProvinceLabel} from './IdentityLabel';
import {advanceName, label} from './labels';
import {t} from './messages';
import {HelpLink} from './HelpLink';
import {Commodity, UnplayedLeaders} from './TablePanels';
import {MapPicker, PlayerPicker, useMapSelection} from './map/MapSelection';
import {InvalidChoice, useTargets} from './DecisionControls';
import {CardEventText} from './CardEventText';
import {CardPlayEffects} from './CardPlayEffects';
import {AdvanceBenefits} from './AdvanceBenefits';
import {LeaderCredit} from './LeaderCredit';
import {AdvancePrice} from './AdvancePrice';
import {MiseryPreview} from './MiseryPreview';
import {CardRecurrence} from './CardRecurrence';

interface Props {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>;
    mode?: 'inspection' | 'action' | 'all';
    inspectedId?: string;
    historical?: boolean;
    closeInspection?: () => void;
}

function choiceLabel(snapshot: Snapshot, choice: CardChoiceView) {
    if (choice.kind === 'PLAYER') return <PlayerLabel snapshot={snapshot} id={choice.value ?? ''}/>;
    if (choice.kind === 'PROVINCE') return <ProvinceLabel snapshot={snapshot} id={choice.value ?? ''}/>;
    if (choice.kind === 'COMMODITY') return <Commodity commodity={choice.value?.toUpperCase() ?? ''}/>;
    if (choice.kind === 'CATEGORY') return choice.value ? label(choice.value.toUpperCase(), 'category') : t('table.noCategory');
    if (choice.kind === 'AREA') return `${t('board.area')} ${choice.value?.toUpperCase()}`;
    return t('action.playCard');
}

export function CardDecision({snapshot, busy, submit, mode = 'all', inspectedId, historical = false, closeInspection}: Props) {
    const {selectedCard, setSelectedCard} = useMapSelection();
    const reference = inspectedId ?? selectedCard;
    const [choice, setChoice] = useState('');
    const [protection, setProtection] = useState('0');
    useEffect(() => { setChoice(''); setProtection('0'); }, [selectedCard]);
    const held = historical ? undefined : snapshot.game.hand.find(candidate => candidate.reference === reference);
    const info = snapshot.game.cardInspections?.find(candidate => candidate.definition.id === (historical ? reference : held?.definition.id));
    const definition = held?.definition ?? info?.definition;
    const offer = historical ? undefined : snapshot.game.cardOffers.find(candidate => candidate.reference === reference);
    const selection = useTargets(offer?.provinceTargets ?? [], offer?.requiredTargetCount ?? 0);
    const targets = selection.selected;
    if (!definition) return null;
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
    const resolutionChoice = selectedChoice ?? (noChoice ? finiteChoices[0] : undefined);
    const miseryChanges = resolutionChoice?.miseryChanges ?? offer?.miseryChanges ?? [];
    const uncertainPlayers = resolutionChoice?.miseryUncertainPlayers ?? offer?.miseryUncertainPlayers ?? [];
    const cashChanges = !needsProtection || validProtection && amount === 0
        ? resolutionChoice?.cashChanges ?? [] : [];
    const chooseMiseryTarget = !resolutionChoice && !miseryChanges.length && !uncertainPlayers.length &&
        finiteChoices.some(candidate => (candidate.miseryChanges?.length ?? 0) > 0 || (candidate.miseryUncertainPlayers?.length ?? 0) > 0);
    return <section className="object-decision card-decision" aria-labelledby={inspection ? undefined : 'decision-title'}>
        {inspection && <><button type="button" className="close-detail" aria-label={t('table.close')} onClick={closeInspection ?? (() => setSelectedCard(''))}>×</button>
            <h2>{t(definition.messageId)}</h2><CardRecurrence definition={definition}/>
            <CardEventText definition={definition}/></>}
        {action && offer?.playable && definition.kind === 'leader' && <p className="card-play-credit-context">{t('card.leaderEffect')}</p>}
        {(info?.leaderDiscounts ?? offer?.leaderDiscounts)?.map(discount => <details key={discount.advance} className="leader-discount-detail">
            <summary>{advanceName(discount.advance)} <LeaderCredit amount={discount.effectiveDiscount} writtenRecordBonus={discount.writtenRecordBonus}/></summary>
            <dl className="detail-list"><div><dt>{t('table.printedDiscount')}</dt><dd>${discount.printedAmount}</dd></div>
                <div><dt>{advanceName('written-record')}</dt><dd>${discount.writtenRecordBonus}</dd></div>
                <div><dt>{t('table.conditionalDiscount')}</dt><dd>${discount.conditionalAmount}</dd></div></dl>
            {discount.condition && <p>{label(discount.condition)}</p>}
        </details>)}
        {inspection && offer?.blockers.map(blocker => <p className="blocker" key={blocker}>{t(`error.${blocker}`)}</p>)}
        {action && offer?.playable && <DecisionForm onSubmit={async event => { event.preventDefault(); if (actionable && command) await submit(command); }}>
            <CardPlayEffects snapshot={snapshot} definition={definition} choice={resolutionChoice}
                commodities={offer.commodities} targets={targets}
                protection={needsProtection && validProtection ? amount : null}/>
            {!noChoice && finiteChoices.length > 0 && <fieldset className="target-choices"><legend>{t('controls.cardChoice')}</legend>
                {finiteChoices.map(candidate => <button type="button" key={candidate.command} className="secondary"
                    aria-pressed={candidate.command === choice} onClick={() => { setChoice(candidate.command ?? ''); setProtection(''); }}>
                    {choiceLabel(snapshot, candidate)}</button>)}
            </fieldset>}
            {finiteChoices.some(candidate => candidate.kind === 'PLAYER') && <PlayerPicker disabled={busy}
                eligible={finiteChoices.filter(candidate => candidate.kind === 'PLAYER').flatMap(candidate => candidate.value ? [candidate.value] : [])}
                selected={selectedChoice?.value ? [selectedChoice.value] : []}
                onPick={id => setChoice(finiteChoices.find(candidate => candidate.kind === 'PLAYER' && candidate.value === id)?.command ?? '')}/>}
            {provinceChoices.length > 0 && <MapPicker title={t('table.pickTarget')} disabled={busy} eligible={provinceChoices.flatMap(candidate => candidate.value ? [candidate.value] : [])}
                selected={selectedChoice?.value ? [selectedChoice.value] : []} onPick={id => setChoice(provinceChoices.find(candidate => candidate.value === id)?.command ?? '')}/>}
            {areaChoices.length > 0 && <MapPicker kind="area" title={t('table.pickTarget')} disabled={busy}
                eligible={areaChoices.flatMap(candidate => candidate.value ? [candidate.value.toUpperCase()] : [])}
                selected={selectedChoice?.kind === 'AREA' && selectedChoice.value ? [selectedChoice.value.toUpperCase()] : []}
                onPick={area => setChoice(areaChoices.find(candidate => candidate.value?.toUpperCase() === area)?.command ?? '')}/>}
            {needsTargets && offer && <fieldset className="target-choices"><legend>{t(offer.requiredTargetCount === 1 ? 'table.pickTarget' : 'table.pickTargets', {count: offer.requiredTargetCount ?? 0})} · {targets.length}/{offer.requiredTargetCount}</legend>
                {offer.provinceTargets.map(province => <button type="button" key={province} className="secondary" aria-pressed={targets.includes(province)}
                    disabled={busy || !selection.canPick(province)}
                    onClick={() => selection.pick(province)}><ProvinceLabel snapshot={snapshot} id={province}/></button>)}
                <MapPicker title={t(offer.requiredTargetCount === 1 ? 'table.pickTarget' : 'table.pickTargets', {count: offer.requiredTargetCount ?? 0})} disabled={busy} selected={targets}
                    eligible={offer.provinceTargets.filter(target => selection.canPick(target))}
                    onPick={selection.pick}/>
            </fieldset>}
            {needsProtection && <label>{t('game.protection')}<input type="number" min={0} max={offer?.protectionMax ?? 0} value={protection}
                onChange={event => {setProtection(event.target.value); setChoice('');}}/></label>}
            {needsProtection && protection.trim() !== '' && !validProtection && <p className="blocker">{t('table.invalidProtection').replace('{max}', String(offer?.protectionMax))}</p>}
            <InvalidChoice invalid={!!(choice && !selectedChoice) || invalidTargets} clear={() => {setChoice(''); selection.clear();}}/>
            {cashChanges.map(change => <p className="cash-preview" key={change.player}>
                <LabelMessage text={t(change.after >= change.before ? 'card.receivesCash' : 'card.losesCash', {amount: Math.abs(change.after - change.before)})}
                    values={{player: <PlayerLabel snapshot={snapshot} id={change.player}/>}}/>
                {' · '}{t('game.cash')}: ${change.before} → ${change.after}
            </p>)}
            <MiseryPreview snapshot={snapshot} changes={miseryChanges} uncertainPlayers={uncertainPlayers}/>
            {chooseMiseryTarget && <p className="small muted">{t('controls.chooseMiseryTarget')}</p>}
            <DecisionAction type="submit" className="card-confirmation play-card-action" disabled={busy || !actionable}>{t('action.playCard')}</DecisionAction>
        </DecisionForm>}
    </section>;
}

export function AdvanceDecision({snapshot, busy, submit, mode = 'all', inspectedId, closeInspection}: Props) {
    const {selectedAdvance, setSelectedAdvance} = useMapSelection();
    const advance = snapshot.game.catalog?.advances.find(candidate => candidate.id === (inspectedId ?? selectedAdvance));
    if (!advance) return null;
    const offer = snapshot.game.advanceOffers.find(candidate => candidate.advance === advance.id);
    const command = `buy-advance ${advance.letter}`;
    const available = offer && offer.unavailableReason === null && snapshot.legalChoices.commands.includes(command);
    const me = snapshot.game.players.find(player => player.id === snapshot.viewerId);
    const inspection = mode !== 'action';
    const action = mode !== 'inspection';
    return <section className="object-decision advance-decision" aria-labelledby={inspection ? undefined : 'decision-title'}>
        {inspection && <><button type="button" className="close-detail" aria-label={t('table.close')} onClick={closeInspection ?? (() => setSelectedAdvance(''))}>×</button>
            <p className="eyebrow">{label(advance.category, 'category')}</p><h2>{advanceName(advance.id)}</h2></>}
        <AdvanceBenefits id={advance.id} rules={snapshot.game.rules}/>
        <p className="advance-prerequisites">{t('advance.prerequisites')}: {advance.prerequisites.map(advanceName).join(', ') || t('reference.none')}</p>
        <p className="advance-relief">{t('advance.reliefRating', {amount: advance.miseryRelief})}</p>
        {inspection && <><HelpLink topic={`advance-${advance.id.toLowerCase()}`} label={t('reference.printedValues')} compact={false}/>
            <UnplayedLeaders snapshot={snapshot} advance={advance.id}/></>}
        <AdvancePrice offer={offer} printedCost={advance.cost}/>
        {offer?.price !== null && offer?.price !== undefined && <>
            <p className="small">{t('controls.remainingCash')}: ${(me?.cash ?? 0) - offer.price}</p>
        </>}
        {inspection && offer?.unavailableReason && <p className="blocker">{t(`error.${offer.unavailableReason}`)}</p>}
        {inspection && <p>{t('advance.owners')}: <PlayerLabels snapshot={snapshot} ids={snapshot.game.players.filter(player => player.advances.includes(advance.id)).map(player => player.id)}/></p>}
        <MiseryPreview snapshot={snapshot} changes={offer?.miseryChanges ?? []}/>
        {action && offer && <DecisionAction className="card-confirmation buy-advance-action" disabled={busy || !available} onClick={async () => { if (available && await submit(command)) setSelectedAdvance(''); }}>{t('action.buyAdvance')}</DecisionAction>}
    </section>;
}
