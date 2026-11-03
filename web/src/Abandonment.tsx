import {useEffect, useState} from 'react';
import type {Snapshot} from './api';
import {PlayerLabel, PlayerLabels} from './IdentityLabel';
import {CardInspectionDialog} from './CardInspectionDialog';
import {t} from './messages';

export type AbandonmentOperation = 'propose' | 'agree' | 'decline' | 'withdraw';
export type WriteAbandonment = (operation: AbandonmentOperation, requestId?: string) => Promise<boolean>;

export function AbandonmentTools({snapshot, busy, write}: {snapshot: Snapshot; busy: boolean; write: WriteAbandonment}) {
    const [confirming, setConfirming] = useState(false);
    const eligible = snapshot.viewerId !== null && (!snapshot.status || snapshot.status === 'ACTIVE') && snapshot.game.stage !== 'FINISHED';
    const controlsBots = snapshot.viewerId === snapshot.creatorId && !!snapshot.automation?.players.length;
    const botIds = snapshot.automation?.players.map(player => player.id) ?? [];
    const endsImmediately = controlsBots && snapshot.game.players.every(player => player.id === snapshot.viewerId || botIds.includes(player.id));
    useEffect(() => {if (!eligible || snapshot.abandonment) setConfirming(false);}, [eligible, snapshot.abandonment]);
    if (!eligible || snapshot.abandonment) return null;
    return <><button type="button" className="secondary abandon-game" disabled={busy} onClick={() => setConfirming(true)}>{t('abandonment.open')}</button>
        {confirming && <CardInspectionDialog label={t('abandonment.title')} close={() => setConfirming(false)}>
            <h2>{t('abandonment.title')}</h2><p>{t(endsImmediately ? 'abandonment.soloConfirm' : 'abandonment.confirm')}</p>
            {controlsBots && <p>{t('abandonment.botAgreement')}</p>}
            <div className="button-row"><button type="button" disabled={busy} onClick={async () => {if (await write('propose')) setConfirming(false);}}>{t(endsImmediately ? 'abandonment.agreeFinal' : 'abandonment.request')}</button>
                <button type="button" className="secondary" onClick={() => setConfirming(false)}>{t('abandonment.back')}</button></div>
        </CardInspectionDialog>}
    </>;
}

export function AbandonmentStatus({snapshot, busy, write}: {snapshot: Snapshot; busy: boolean; write?: WriteAbandonment}) {
    const request = snapshot.abandonment;
    if (!request) return null;
    const agreed = request.agreed.includes(snapshot.viewerId ?? '');
    const botIds = snapshot.viewerId === snapshot.creatorId ? snapshot.automation?.players.map(player => player.id) ?? [] : [];
    const final = request.outstanding.every(player => player === snapshot.viewerId || botIds.includes(player));
    return <section className="abandonment-status" aria-label={t('abandonment.title')}>
        <div role="status" aria-live="polite"><h3>{t('abandonment.title')}</h3>
            <p>{t('abandonment.requestedBy')} <PlayerLabel snapshot={snapshot} id={request.proposer}/></p>
            <p>{t('abandonment.agreed')}: <PlayerLabels snapshot={snapshot} ids={request.agreed}/></p>
            <p>{t('abandonment.awaiting')}: <PlayerLabels snapshot={snapshot} ids={request.outstanding}/></p>
        </div>
        <p className="small">{t(final && !agreed ? 'abandonment.finalConsequence' : 'abandonment.consequence')}</p>
        {!agreed && botIds.length > 0 && <p className="small">{t('abandonment.botAgreement')}</p>}
        {write && snapshot.viewerId && <div className="button-row">
            {request.operations.includes('AGREE') && <button type="button" disabled={busy} onClick={() => {void write('agree', request.requestId);}}>{t(final ? 'abandonment.agreeFinal' : 'abandonment.agree')}</button>}
            {request.operations.includes('DECLINE') && <button type="button" className="secondary" disabled={busy} onClick={() => {void write('decline', request.requestId);}}>{t('abandonment.keepPlaying')}</button>}
            {request.operations.includes('WITHDRAW') && <><span>{t('abandonment.youAgreed')}</span><button type="button" className="secondary" disabled={busy} onClick={() => {void write('withdraw', request.requestId);}}>{t(request.proposer === snapshot.viewerId ? 'abandonment.cancel' : 'abandonment.withdraw')}</button></>}
        </div>}
    </section>;
}
