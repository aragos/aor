import {useEffect, useRef, useState} from 'react';
import type {PlannedAction, PlanningQuote, PlanOperation, Snapshot} from './api';
import {errorCode} from './api';
import {DecisionAction} from './DecisionActions';
import {advanceName, label, purchasePassLabel} from './labels';
import {PlayerLabel} from './IdentityLabel';
import {MiseryBox} from './MiseryBox';
import {HelpLink} from './HelpLink';
import {t} from './messages';

export type WritePlan = (operation: PlanOperation, actions?: PlannedAction[], previewToken?: string, expectedVersion?: string | null) => Promise<Snapshot | null>;
export type QuotePlan = (actions: PlannedAction[]) => Promise<PlanningQuote>;

/** Local drafts follow the phase and owner, not polling revisions or object inspection. */
export function useActivityPlanner(snapshot: Snapshot, write?: WritePlan, quote?: QuotePlan) {
    const [open, setOpen] = useState(false);
    const [actions, setActions] = useState<PlannedAction[]>([]);
    const [quoted, setQuoted] = useState<{key: string; value: PlanningQuote} | null>(null);
    const [error, setError] = useState('');
    const [baseVersion, setBaseVersion] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);
    const request = useRef(quote);
    request.current = quote;
    const scope = `${snapshot.gameId}:${snapshot.viewerId}:${snapshot.game.turnNumber}:${snapshot.game.stage}:${snapshot.undo?.editRevision}`;
    useEffect(() => {setOpen(false); setActions([]); setQuoted(null); setError(''); setBaseVersion(null); setSaving(false);}, [scope]);
    useEffect(() => {if (!snapshot.planning?.available) setOpen(false);}, [snapshot.planning?.available]);
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
    const saved = snapshot.planning?.saved;
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
    const displayed = preview ?? quoted?.value;
    const effective: Snapshot = open && displayed ? {
        ...snapshot, game: displayed.preview.game, legalChoices: displayed.preview.choices,
        prompt: displayed.preview.decision ? {messageId: `prompt.${displayed.preview.decision}`, players: [snapshot.viewerId!]} : null,
    } : snapshot;
    return {open, actions, preview, error, dirty, effective, edit, operate, append,
        conflict: open && baseVersion !== (snapshot.planning?.version ?? null) && !saving,
        close: () => setOpen(false), removeFrom: (index: number) => setActions(current => current.slice(0, index)),
        loading: open && !preview, supported: !!write && !!quote && !!snapshot.planning?.available};
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
        case 'pass': return purchasePassLabel({...snapshot, prompt: {messageId: `prompt.${action.decision}`, players: []}});
        case 'stabilize': return t(`action.stabilize ${argument}`);
        case 'relieve-misery': return `${t('action.relieveMisery')} · ${argument} ${t('controls.spaces')}`;
        case 'patronize': return `${t('action.patronize')} · ${t(`leader.${argument.toUpperCase().replace(/-/g, '_')}`)}`;
        default: return t(`action.${verb}`);
    }
}

export function ActivityPlanner({snapshot, planner, busy}: {
    snapshot: Snapshot; planner: ReturnType<typeof useActivityPlanner>; busy: boolean;
}) {
    if (!planner.supported) return null;
    const view = snapshot.planning!;
    const saved = view.saved;
    const preview = planner.open ? planner.preview?.preview : view.preview;
    const actions = planner.open ? planner.actions : saved?.actions ?? [];
    const ready = !busy && (!planner.open || !!planner.preview);
    return <section className="activity-planner" aria-label={t('plan.title')}>
        <h3>{t(planner.open ? 'plan.editing' : 'plan.title')} <HelpLink topic="plans" label={t('plan.helpTitle')}/></h3>
        <p className="private-draft-status" role="status">{t(saved?.armed ? 'plan.armed' : planner.open && planner.dirty ? 'plan.unsaved' : saved ? 'plan.saved' : 'plan.introduction')}</p>
        {saved?.pause && <p className="blocker" role="status">{t(`plan.pause.${saved.pause}`)}</p>}
        {preview?.hypothetical && <p className="small">{t('plan.assumption')}</p>}
        {actions.length > 0 && <ol className="plan-actions">{actions.map((action, index) => {
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
        {preview?.diagnostic && preview.invalidIndex == null && <p className="blocker">{t(`error.${preview.diagnostic.code}`)}</p>}
        {preview?.boundary === 'INFORMATION' && <p>{t('plan.drawBoundary')}</p>}
        {preview?.boundary === 'FINISHED' && planner.open && <p>{t('plan.complete')}</p>}
        {preview?.boundary === 'INCOMPLETE' && actions.length > 0 && <p>{t('plan.partial')}</p>}
        {planner.error && <p className="error" role="alert">{t(`error.${planner.error}`)}</p>}
        {planner.conflict && <p className="blocker" role="status">{t('plan.changedElsewhere')}
            <button type="button" disabled={busy} onClick={() => {void planner.edit();}}>{t('plan.loadSaved')}</button></p>}
        {planner.loading && <p role="status">{t('table.quotePending')}</p>}
        {!planner.open ? <button type="button" className="secondary" disabled={busy} onClick={() => {void planner.edit();}}>{t(saved ? 'plan.edit' : 'plan.prepare')}</button>
            : <button type="button" className="secondary" disabled={busy} onClick={planner.close}>{t('plan.close')}</button>}
        {saved?.armed ? <DecisionAction className="secondary" disabled={busy} onClick={() => {void planner.operate('DISARM');}}>{t('plan.cancelAutomatic')}</DecisionAction> : <>
            {planner.open && <DecisionAction disabled={!ready || planner.conflict} onClick={() => {void planner.operate('SAVE');}}>{t('plan.save')}</DecisionAction>}
            {saved && (!planner.open || !planner.dirty) && <DecisionAction disabled={!ready || planner.conflict || !view.canArm || !preview?.effects.length || preview.boundary === 'INVALID'}
                onClick={() => {void planner.operate(view.canExecute ? 'EXECUTE' : 'ARM');}}>{t(view.canExecute ? 'plan.execute' : 'plan.automatic')}</DecisionAction>}
        </>}
        {saved && <button type="button" className="secondary" disabled={busy} onClick={() => {void planner.operate('DELETE');}}>{t('plan.delete')}</button>}
    </section>;
}
