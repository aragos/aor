import {useEffect, useRef, useState} from 'react';
import type {Snapshot} from './api';
import {t} from './messages';
import {advanceName} from './labels';

/** A private browser draft submits only when the authoritative turn offers buying a card. */
export function UrbanAscendancyQueue({snapshot, busy, submit}: {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>;
}) {
    const [queuedFor, setQueuedFor] = useState<string | null>(null);
    const submitting = useRef(false);
    const scope = `${snapshot.gameId}:${snapshot.viewerId}:${snapshot.game.turnNumber}:${snapshot.undo?.editRevision}`;
    const player = snapshot.game.players.find(candidate => candidate.id === snapshot.viewerId);
    const actorIndex = snapshot.game.turnOrder.indexOf(snapshot.game.activity?.actor ?? '');
    const playerIndex = snapshot.game.turnOrder.indexOf(snapshot.viewerId);
    const offer = snapshot.game.cardPurchaseOffer;
    const eligible = snapshot.game.stage === 'BUY_CARD' && player?.misery !== null &&
        player?.advances.includes('URBAN_ASCENDANCY') && (offer ? offer.blockers.length === 0 : player.cash >= 10 &&
        (snapshot.game.deckRemaining > 0 || snapshot.game.currentEpoch < 3)) && actorIndex >= 0 && playerIndex >= actorIndex;
    const queued = queuedFor === scope;
    const canBuy = snapshot.legalChoices.commands.includes('buy-card');
    const waiting = eligible && playerIndex > actorIndex;
    useEffect(() => {
        if (queuedFor && (!eligible || queuedFor !== scope)) setQueuedFor(null);
    }, [eligible, queuedFor, scope]);
    useEffect(() => {
        if (!queued || !canBuy || busy || submitting.current) return;
        submitting.current = true;
        // Consume before sending: polling or a rejected request must never repeat a purchase.
        setQueuedFor(null);
        void submit('buy-card').catch(() => false).finally(() => { submitting.current = false; });
    }, [busy, canBuy, queued, submit]);
    if (!player || (!waiting && !queued)) return null;
    return <section className="urban-ascendancy-queue" aria-label={advanceName('URBAN_ASCENDANCY')}>
        <p>{advanceName('URBAN_ASCENDANCY')} · ${offer?.cost ?? 10}<br/>{t('controls.availableCash')}: ${player.cash}</p>
        {queued ? <>
            <p role="status">{t('urbanAscendancy.queued')}</p>
            <button type="button" className="secondary" disabled={busy}
                onClick={() => setQueuedFor(null)}>{t('urbanAscendancy.cancel')}</button>
        </> : <button type="button" className="secondary" disabled={busy}
            onClick={() => setQueuedFor(scope)}>{t('urbanAscendancy.buyOnTurn')}</button>}
    </section>;
}
