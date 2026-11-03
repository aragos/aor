import {useEffect, useRef, useState} from 'react';
import type {RewindTarget, RewindTargets, Snapshot} from './api';
import {errorCode} from './api';
import {CardInspectionDialog} from './CardInspectionDialog';
import {t} from './messages';
import {PlayerLabel} from './IdentityLabel';
import {displayHistoryLine} from './history';
import {playerName} from './labels';

export type LoadRewindTargets = (revision: number, before?: number) => Promise<RewindTargets>;

export function RewindReview({snapshot, target, busy, close, submit}: {
    snapshot: Snapshot; target: RewindTarget; busy: boolean; close: () => void;
    submit: (command: string) => Promise<boolean>;
}) {
    const base = useRef(snapshot.revision).current;
    const stale = base !== snapshot.revision;
    return <CardInspectionDialog label={t('history.rewind')} close={close}>
        <button type="button" className="close-detail" aria-label={t('table.close')} onClick={close}>×</button>
        <h2>{t('history.rewind')}</h2>
        <p>{t('history.destination', {turn: target.turnNumber ?? '—', stage: t(`stage.${target.stage}`)})}</p>
        <p><PlayerLabel snapshot={snapshot} id={target.actor}/> · {displayHistoryLine(target.actionText, Object.fromEntries(snapshot.game.players.map(player => [player.id, playerName(snapshot, player.id)])))}</p>
        <p>{t('history.rewindConsequences')}</p>
        {target.removesRandomness && <p>{t('history.randomnessRemoved')}</p>}
        {stale && <p className="blocker">{t('history.staleDraft')}</p>}
        <button type="button" disabled={busy || stale} onClick={async () => {
            if (await submit(`undo ${target.entryCount}`)) close();
        }}>{t('history.rewindHere')}</button>
    </CardInspectionDialog>;
}

export function RewindHistory({snapshot, busy, load, review}: {
    snapshot: Snapshot; busy: boolean; load: LoadRewindTargets;
    review: (target: RewindTarget) => void;
}) {
    const [open, setOpen] = useState(false);
    const [page, setPage] = useState<RewindTargets | null>(null);
    const [error, setError] = useState('');
    const loader = useRef(load); loader.current = load;
    useEffect(() => {
        setPage(null); setError('');
        if (!open) return;
        let active = true;
        void loader.current(snapshot.revision).then(value => {if (active) setPage(value);})
            .catch(failure => {if (active) setError(errorCode(failure));});
        return () => {active = false;};
    }, [open, snapshot.revision]);
    return <details open={open} onToggle={event => setOpen(event.currentTarget.open)}>
        <summary>{t('history.earlierTargets')}</summary>
        {error && <p role="alert">{t(`error.${error}`)}</p>}
        <ol className="rewind-targets">{page?.targets.map(target => <li key={target.entryCount}>
            <button type="button" className="secondary" disabled={busy || page.expectedRevision !== snapshot.revision}
                onClick={() => review(target)}>{t('history.destination', {turn: target.turnNumber ?? '—', stage: t(`stage.${target.stage}`)})} · {target.entryCount} · {displayHistoryLine(target.actionText, Object.fromEntries(snapshot.game.players.map(player => [player.id, playerName(snapshot, player.id)])))}</button>
        </li>)}</ol>
        {page?.nextBefore != null && <button type="button" className="secondary" disabled={busy} onClick={async () => {
            try {
                const next = await load(page.expectedRevision, page.nextBefore!);
                setPage(current => current?.expectedRevision === next.expectedRevision
                    ? {...next, targets: [...current.targets, ...next.targets]} : current);
            } catch (failure) {setError(errorCode(failure));}
        }}>{t('history.moreTargets')}</button>}
    </details>;
}
