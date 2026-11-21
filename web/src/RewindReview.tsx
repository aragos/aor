import {useEffect, useLayoutEffect, useRef, useState} from 'react';
import type {ApiError, RewindTarget, RewindTargets, Snapshot} from './api';
import {errorCode} from './api';
import {CardInspectionDialog} from './CardInspectionDialog';
import {DiagnosticMessage} from './DiagnosticMessage';
import {t} from './messages';
import {displayHistoryLine} from './history';
import {playerName} from './labels';

export type LoadRewindTargets = (revision: number, before?: number) => Promise<RewindTargets>;

interface Selection {target: RewindTarget; revision: number}

export function UndoControls({snapshot, busy, submit, load, actionError}: {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>;
    load?: LoadRewindTargets; actionError: ApiError | null;
}) {
    const [dialog, setDialog] = useState<'latest' | 'history' | null>(null);
    const [selection, setSelection] = useState<Selection | null>(null);
    const withdrawing = snapshot.legalChoices.commands.includes('withdraw-bid');
    const latest = snapshot.undo?.latestTarget;
    const historyAvailable = !!(load && latest && snapshot.undo.capability === 'PREFIX_REWIND');
    const withdrawLabel = t(snapshot.game.stage === 'TOKEN_PURCHASE' ? 'controls.withdrawPurchase' : 'controls.withdrawBid');
    const close = () => {setDialog(null); setSelection(null);};
    async function undoLatest() {
        if (withdrawing) {
            await submit('withdraw-bid');
        } else if (!latest) {
            await submit('undo');
        } else if (latest.removesRandomness || latest.actor !== snapshot.viewerId || snapshot.submitted.length > 0 ||
            latest.stage !== snapshot.game.stage || latest.turnNumber !== snapshot.game.turnNumber) {
            setSelection({target: latest, revision: snapshot.revision});
            setDialog('latest');
        } else {
            await submit(`undo ${latest.entryCount}`);
        }
    }

    return <>
        {(withdrawing || snapshot.undo?.available) && <div className={historyAvailable ? 'undo-controls has-history' : 'undo-controls'}>
            <button type="button" className={`secondary undo-action ${withdrawing ? 'withdraw-bid' : ''}`} disabled={busy}
                title={withdrawing ? withdrawLabel : t('history.undoLast')}
                aria-label={withdrawing ? `${t('history.undo')}: ${withdrawLabel}` : t('history.undo')}
                onClick={() => {void undoLatest();}}>{t('history.undo')}</button>
            {historyAvailable && <button type="button" className="secondary undo-history" disabled={busy}
                aria-label={t('history.undoHistory')} title={t('history.undoHistory')} aria-haspopup="dialog"
                onClick={() => setDialog('history')}><span aria-hidden="true">▾</span></button>}
        </div>}
        {dialog && <RewindDialog snapshot={snapshot} busy={busy} submit={submit} actionError={actionError}
            load={dialog === 'history' ? load : undefined} selection={selection} select={setSelection} close={close}/>}
    </>;
}

function RewindDialog({snapshot, selection, select, busy, close, submit, load, actionError}: {
    snapshot: Snapshot; selection: Selection | null; select: (selection: Selection | null) => void;
    busy: boolean; close: () => void; submit: (command: string) => Promise<boolean>;
    load?: LoadRewindTargets; actionError: ApiError | null;
}) {
    const [attempted, setAttempted] = useState(false);
    const heading = useRef<HTMLHeadingElement>(null);
    const checkpoint = useRef<HTMLElement | null>(null);
    useLayoutEffect(() => {
        const focus = !selection && checkpoint.current?.isConnected ? checkpoint.current : heading.current;
        focus?.focus({preventScroll: true});
    }, [selection]);
    const label = t(load ? selection ? 'history.confirmCheckpoint' : 'history.undoHistory' : 'history.undoLast');
    const target = selection?.target;
    const stale = !!selection && selection.revision !== snapshot.revision;
    return <CardInspectionDialog label={label} close={close} className="rewind-dialog">
        <button type="button" className="close-detail" aria-label={t('table.close')} onClick={close}>×</button>
        <h2 ref={heading} tabIndex={-1}>{label}</h2>
        {target ? <>
            <p><RewindPosition snapshot={snapshot} target={target} checkpoint={!!load}/></p>
            {(load || target.stage !== snapshot.game.stage || target.turnNumber !== snapshot.game.turnNumber ||
                snapshot.submitted.length > 0) && <p>{t('history.checkpointConsequences')}</p>}
            {target.removesRandomness && <p>{t('history.randomnessWarning')}</p>}
            {stale && <p className="blocker" role="status">{t('history.staleRewind')}</p>}
            {attempted && actionError && !stale && <DiagnosticMessage failure={actionError} snapshot={snapshot}/>}
            <div className="button-row">
                <button type="button" disabled={busy || stale} onClick={async () => {
                    setAttempted(true);
                    if (await submit(`undo ${target.entryCount}`)) close();
                }}>{t(load ? 'history.rewindHere' : 'history.undoLast')}</button>
                <button type="button" className="secondary" onClick={() => {
                    if (load) {setAttempted(false); select(null);}
                    else close();
                }}>{t(load ? 'abandonment.back' : 'action.cancel')}</button>
            </div>
        </> : null}
        {load && <div hidden={!!target}><RewindHistory key={snapshot.revision} snapshot={snapshot} busy={busy} load={load}
            review={(target, button) => {
                checkpoint.current = button;
                select({target, revision: snapshot.revision});
            }}/></div>}
    </CardInspectionDialog>;
}

function RewindPosition({snapshot, target, checkpoint}: {snapshot: Snapshot; target: RewindTarget; checkpoint: boolean}) {
    const aliases = Object.fromEntries(snapshot.game.players.map(player => [player.id, playerName(snapshot, player.id)]));
    return <>
        <strong>{t(checkpoint ? 'history.checkpointPosition' : 'history.destination',
            {turn: target.turnNumber ?? '—', stage: t(`stage.${target.stage}`), entry: target.entryCount})}</strong>
        <span>{t('history.beforeAction', {action: displayHistoryLine(target.actionText, aliases)})}</span>
    </>;
}

function RewindHistory({snapshot, busy, load, review}: {
    snapshot: Snapshot; busy: boolean; load: LoadRewindTargets; review: (target: RewindTarget, button: HTMLButtonElement) => void;
}) {
    const [page, setPage] = useState<RewindTargets | null>(null);
    const [before, setBefore] = useState<number | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState('');
    const loader = useRef(load);
    loader.current = load;
    useEffect(() => {
        let active = true;
        void loader.current(snapshot.revision, before ?? undefined).then(next => {
            if (!active) return;
            setPage(current => current ? {...next, targets: [...current.targets, ...next.targets]} : next);
        }).catch(failure => {
            if (active) setError(errorCode(failure));
        }).finally(() => {if (active) setLoading(false);});
        return () => {active = false;};
    }, [before, snapshot.revision]);
    return <>
        {loading && <p role="status">{t('history.loadingTargets')}</p>}
        {error && <p className="error" role="alert">{t(`error.${error}`)}</p>}
        {page?.targets.length === 0 && <p>{t('history.noTargets')}</p>}
        {!!page?.targets.length && <ol className="rewind-targets">{page.targets.map(target => <li key={target.entryCount}>
            <button type="button" className="secondary" disabled={busy || page.expectedRevision !== snapshot.revision}
                onClick={event => review(target, event.currentTarget)}><RewindPosition snapshot={snapshot} target={target} checkpoint/></button>
        </li>)}</ol>}
        {page?.nextBefore != null && <button type="button" className="secondary" disabled={busy || loading || !!error}
            onClick={() => {setLoading(true); setBefore(page.nextBefore);}}>{t('history.moreTargets')}</button>}
    </>;
}
