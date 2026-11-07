import {useEffect, useRef, useState} from 'react';
import type {PlannedAction, PlanningQuote, PlanOperation, Snapshot} from './api';
import {errorCode} from './api';
import {DecisionAction} from './DecisionActions';
import {advanceName, label} from './labels';
import {PlayerLabel} from './IdentityLabel';
import {MiseryBox} from './MiseryBox';
import {ButtonHelp} from './HelpLink';
import {t} from './messages';

export type WritePlan = (operation: PlanOperation, actions?: PlannedAction[], previewToken?: string, expectedVersion?: string | null) => Promise<Snapshot | null>;
export type QuotePlan = (actions: PlannedAction[]) => Promise<PlanningQuote>;

/** Local drafts follow the phase and owner, not polling revisions or object inspection. */
export function useActivityPlanner(snapshot: Snapshot, write?: WritePlan, quote?: QuotePlan) {
    const [editing, setOpen] = useState(false);
    const saved = snapshot.planning?.saved;
    const cardPurchase = snapshot.game.stage === 'BUY_CARD' &&
        !saved?.actions.some(action => !['buy-card', 'pass'].includes(action.command));
    const acting = snapshot.viewerId !== null &&
        (snapshot.prompt?.players.includes(snapshot.viewerId) ?? false);
    const available = !!snapshot.planning?.available && (!acting || !!saved || editing);
    const open = editing && available && !cardPurchase;
    const [actions, setActions] = useState<PlannedAction[]>([]);
    const [quoted, setQuoted] = useState<{key: string; value: PlanningQuote} | null>(null);
    const [error, setError] = useState('');
    const [baseVersion, setBaseVersion] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const [choosingCard, setChoosingCard] = useState(false);
    const cardRequest = useRef<object | null>(null);
    const request = useRef(quote);
    request.current = quote;
    const scope = `${snapshot.gameId}:${snapshot.viewerId}:${snapshot.status}:${snapshot.game.turnNumber}:${snapshot.game.stage}:${snapshot.undo?.editRevision}`;
    useEffect(() => {setOpen(false); setActions([]); setQuoted(null); setError(''); setBaseVersion(null); setSaving(false);}, [scope]);
    useEffect(() => {
        setChoosingCard(false);
        return () => {cardRequest.current = null;};
    }, [scope]);
    useEffect(() => {if (!available) setOpen(false);}, [available]);
    const key = JSON.stringify([snapshot.revision, actions]);
    useEffect(() => {
        if (!open || !request.current || !snapshot.planning?.available) return;
        let active = true;
        const timer = setTimeout(() => {
            void request.current!(actions).then(value => {
                if (active) {setQuoted({key, value}); setError('');}
            }).catch(failure => {if (active) setError(errorCode(failure));});
        }, 120);
        return () => {active = false; clearTimeout(timer);};
    }, [actions, key, open, snapshot.planning?.available]);
    const preview = quoted?.key === key && quoted.value.revision === snapshot.revision ? quoted.value : null;
    const dirty = JSON.stringify(actions) !== JSON.stringify(saved?.actions ?? []);
    async function edit() {
        let view = snapshot.planning;
        if (saved?.armed) {
            const accepted = await write?.('DISARM');
            if (!accepted?.planning?.available || accepted.planning.saved?.armed) return;
            view = accepted.planning;
        }
        setQuoted(null); setActions(view?.saved?.actions ?? []);
        setBaseVersion(view?.version ?? null); setOpen(true);
    }
    async function operate(operation: PlanOperation) {
        const token = open ? preview?.previewToken : snapshot.planning?.previewToken;
        if (operation === 'SAVE') setSaving(true);
        try {
            const expectedVersion = operation === 'DISARM' ? snapshot.planning?.version ?? null
                : open ? baseVersion : snapshot.planning?.version ?? null;
            const accepted = await write?.(operation, operation === 'SAVE' ? actions : undefined, token ?? undefined,
                expectedVersion);
            if (accepted?.planWriteReceipt && operation === 'SAVE') setBaseVersion(accepted.planWriteReceipt.version);
            if (accepted && operation !== 'SAVE') setOpen(false);
            return !!accepted;
        } finally {setSaving(false);}
    }
    async function append(command: string) {
        if (!preview?.preview.decision || saved?.armed) return false;
        setActions(current => [...current, {decision: preview.preview.decision!, command}]);
        return true;
    }
    async function chooseCardPurchase(command: 'buy-card' | 'pass') {
        if (!write || cardRequest.current) return;
        const pending = {};
        cardRequest.current = pending; setChoosingCard(true);
        try {
            let view = snapshot.planning;
            if (view?.saved?.armed) {
                const disarmed = await write('DISARM', undefined, undefined, view.version);
                if (cardRequest.current !== pending || !disarmed?.planning || disarmed.planning.saved?.armed) return;
                view = disarmed.planning;
            }
            const accepted = await write('SAVE', [{decision: 'buy-card', command}], undefined,
                view?.version ?? null);
            if (cardRequest.current !== pending) return;
            const next = accepted?.planning;
            const version = accepted?.planWriteReceipt?.version;
            if (!next?.canArm || !next.previewToken || !version || next.version !== version) return;
            await write(next.canExecute ? 'EXECUTE' : 'ARM', undefined, next.previewToken, version);
        } finally {
            if (cardRequest.current === pending) {
                cardRequest.current = null; setChoosingCard(false);
            }
        }
    }
    const displayed = preview ?? quoted?.value;
    const effective: Snapshot = open && displayed ? {
        ...snapshot, game: displayed.preview.game, legalChoices: displayed.preview.choices,
        prompt: displayed.preview.decision ? {messageId: `prompt.${displayed.preview.decision}`, players: [snapshot.viewerId!]} : null,
    } : snapshot;
    return {open, actions, preview, error, dirty, effective, edit, operate, append,
        cardPurchase, chooseCardPurchase, choosingCard,
        conflict: open && baseVersion !== (snapshot.planning?.version ?? null) && !saving,
        close: () => setOpen(false), removeFrom: (index: number) => setActions(current => current.slice(0, index)),
        loading: choosingCard || open && !preview, supported: !!write && !!quote && available};
}

function CardPurchasePlanner({snapshot, planner, busy}: {
    snapshot: Snapshot; planner: ReturnType<typeof useActivityPlanner>; busy: boolean;
}) {
    const view = snapshot.planning!;
    const saved = view.saved;
    const acting = snapshot.prompt?.players.includes(snapshot.viewerId!);
    const offer = snapshot.game.cardPurchaseOffer;
    const choice = saved?.actions[0]?.command;
    if (!choice && (acting || !offer || offer.blockers.length > 0)) return null;
    const disabled = busy || planner.choosingCard;
    const buyLabel = t('plan.buyCardOnTurn', {cost: offer?.cost ?? '—'});
    const passLabel = t('plan.passOnTurn');
    return <section className="card-purchase-planner" aria-label={t('table.prompt.buy-card')}>
        {!acting && <>
            <p>{t('plan.cardChoiceTiming')}</p>
            {offer && offer.blockers.length === 0 && <p>{t('plan.cashAfterCard', {cash: offer.cashAfter})}</p>}
            <DecisionAction disabled={disabled || !view.canArm || !offer || offer.blockers.length > 0 || saved?.armed && choice === 'buy-card'}
                aria-pressed={!!saved?.armed && choice === 'buy-card'} onClick={() => {void planner.chooseCardPurchase('buy-card');}}>{buyLabel}</DecisionAction>
            <DecisionAction className="secondary" disabled={disabled || !view.canArm || saved?.armed && choice === 'pass'}
                aria-pressed={!!saved?.armed && choice === 'pass'} onClick={() => {void planner.chooseCardPurchase('pass');}}>{passLabel}</DecisionAction>
        </>}
        {choice && <>
            <p role="status">{t(saved?.armed ? 'plan.cardChoiceScheduled' : 'plan.cardChoiceNeedsReview',
                {choice: choice === 'buy-card' ? buyLabel : passLabel})}</p>
            <button type="button" className="secondary" disabled={disabled}
                onClick={() => {void planner.operate('DELETE');}}>{t('plan.cancelCardChoice')}</button>
        </>}
    </section>;
}

function actionName(action: PlannedAction, snapshot: Snapshot): string {
    const [verb, argument] = action.command.split(' ');
    switch (verb) {
        case 'buy-advance': {
            const advance = snapshot.game.catalog?.advances.find(candidate => candidate.letter === argument);
            return `${t('action.buyAdvance')} · ${advance ? advanceName(advance.id) : argument}`;
        }
        case 'discard-card': {
            const card = snapshot.game.hand.find(candidate => candidate.reference === argument);
            return `${t('action.discard')} · ${card ? t(card.definition.messageId) : t('plan.missingCard')}`;
        }
        case 'stabilize': return t(`plan.stabilize ${argument}`);
        case 'relieve-misery': return `${t('action.relieveMisery')} · ${argument} ${t('controls.spaces')}`;
        case 'patronize': return `${t('action.patronize')} · ${label(argument, 'leader')}`;
        default: return t(`action.${verb}`);
    }
}

export function ActivityPlanner({snapshot, planner, busy}: {
    snapshot: Snapshot; planner: ReturnType<typeof useActivityPlanner>; busy: boolean;
}) {
    if (!planner.supported) return null;
    if (planner.cardPurchase) return <CardPurchasePlanner snapshot={snapshot} planner={planner} busy={busy}/>;
    const view = snapshot.planning!;
    const saved = view.saved;
    const preview = planner.open ? planner.preview?.preview : view.preview;
    const actions = planner.open ? planner.actions : saved?.actions ?? [];
    // Continuations still authorize execution; keep their original effect and removal indices.
    const visibleActions = actions.map((action, index) => ({action, index}))
        .filter(({action}) => action.command !== 'pass');
    const invalidContinuation = preview?.invalidIndex != null &&
        actions[preview.invalidIndex]?.command === 'pass';
    const ready = !busy && (!planner.open || !!planner.preview);
    const help = {topic: 'plans', label: t('plan.helpTitle')};
    return <section className="activity-planner" aria-label={t('plan.title')} data-editing={planner.open}>
        {(saved || planner.open && planner.dirty) && <p className="private-draft-status" role="status">{t(saved?.armed ? 'plan.armed' : planner.open && planner.dirty ? 'plan.unsaved' : 'plan.saved')}</p>}
        {saved?.pause && <p className="blocker" role="status">{t(`plan.pause.${saved.pause}`)}</p>}
        {visibleActions.length > 0 && <ol className="plan-actions">{visibleActions.map(({action, index}) => {
            const effect = preview?.effects[index];
            return <li key={`${index}:${action.command}`}>
                <strong>{actionName(action, snapshot)}</strong>
                {effect && <>
                    {effect.cashChanges.map(change => <span className="plan-effect" key={change.player}>
                        <PlayerLabel snapshot={snapshot} id={change.player}/> · ${change.before} → ${change.after}
                    </span>)}
                    {effect.before.misery !== effect.after.misery && <span className="plan-effect">{t('game.misery')}: <MiseryBox value={effect.before.misery} track={snapshot.game.catalog?.miseryTrack}/> → <MiseryBox value={effect.after.misery} track={snapshot.game.catalog?.miseryTrack}/></span>}
                    {JSON.stringify(effect.before.ships) !== JSON.stringify(effect.after.ships) && <span className="plan-effect">{t('game.ships')}: {label(effect.before.ships.fleet, 'fleet')} {effect.before.ships.level} → {label(effect.after.ships.fleet, 'fleet')} {effect.after.ships.level}</span>}
                </>}
                {preview?.invalidIndex === index && preview.diagnostic && <span className="blocker">{t(`error.${preview.diagnostic.code}`)}</span>}
                {planner.open && <button type="button" className="secondary" disabled={busy || saved?.armed}
                    onClick={() => planner.removeFrom(index)} aria-label={`${t('plan.removeFrom')} · ${actionName(action, snapshot)}`}>{t('plan.removeFrom')}</button>}
            </li>;
        })}</ol>}
        {preview?.diagnostic && (preview.invalidIndex == null || invalidContinuation) && <p className="blocker">{t(`error.${preview.diagnostic.code}`)}
            {planner.open && invalidContinuation && <button type="button" className="secondary" disabled={busy || saved?.armed}
                onClick={() => planner.removeFrom(preview.invalidIndex!)}>{t('plan.removeFrom')}</button>}
        </p>}
        {planner.error && <p className="error" role="alert">{t(`error.${planner.error}`)}</p>}
        {planner.conflict && <p className="blocker" role="status">{t('plan.changedElsewhere')}
            <button type="button" disabled={busy} onClick={() => {void planner.edit();}}>{t('plan.loadSaved')}</button></p>}
        {planner.loading && <p role="status">{t('table.quotePending')}</p>}
        {!planner.open ? <ButtonHelp {...help}><button type="button" className="secondary" disabled={busy} onClick={() => {void planner.edit();}}>{t(saved ? 'plan.edit' : 'plan.prepare')}</button></ButtonHelp>
            : <button type="button" className="secondary" disabled={busy} onClick={planner.close}>{t('plan.close')}</button>}
        {saved?.armed ? <DecisionAction help={help} className="secondary" disabled={busy} onClick={() => {void planner.operate('DISARM');}}>{t('plan.cancelAutomatic')}</DecisionAction> : <>
            {planner.open && <DecisionAction help={help} disabled={!ready || planner.conflict} onClick={() => {void planner.operate('SAVE');}}>{t('plan.save')}</DecisionAction>}
            {saved && (!planner.open || !planner.dirty) && <DecisionAction disabled={!ready || planner.conflict || !view.canArm || !preview?.effects.length || preview.boundary === 'INVALID'}
                help={help} onClick={() => {void planner.operate(view.canExecute ? 'EXECUTE' : 'ARM');}}>{t(view.canExecute ? 'plan.execute' : 'plan.automatic')}</DecisionAction>}
        </>}
        {saved && <button type="button" className="secondary" disabled={busy} onClick={() => {void planner.operate('DELETE');}}>{t('plan.delete')}</button>}
    </section>;
}
