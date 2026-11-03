import {useEffect, useState} from 'react';
import type {HistoryEventView, Snapshot} from './api';

interface ResultRequest {
    revision: number; ids: Set<string>; accepted: boolean; turn: number | null;
    kind: 'competition' | 'war'; actor: string; province?: string;
}

/** Local receipts follow interruptions; competition receipts belong to their expansion phase. */
export function useActionResult(snapshot: Snapshot, sendCommand: (command: string) => Promise<boolean>) {
    const [result, setResult] = useState<HistoryEventView | null>(null);
    const [resultRequest, setResultRequest] = useState<ResultRequest | null>(null);
    const inExpansion = (turn: number | null) => snapshot.game.stage === 'EXPANSION' && snapshot.game.turnNumber === turn;
    const active = !snapshot.status || snapshot.status === 'ACTIVE';
    const visibleResult = active && result && snapshot.events.some(event => event.id === result.id) &&
        (!result.competition || inExpansion(result.turnNumber)) ? result : null;
    useEffect(() => {
        if (result && !visibleResult) setResult(null);
    }, [result, visibleResult]);
    useEffect(() => {
        if (!active) {setResultRequest(null); return;}
        if (resultRequest?.kind === 'competition' && !inExpansion(resultRequest.turn)) {
            setResultRequest(null);
            return;
        }
        if (!resultRequest?.accepted || snapshot.revision <= resultRequest.revision) return;
        const outcome = [...snapshot.events].reverse().find(event => !resultRequest.ids.has(event.id) &&
            event.turnNumber === resultRequest.turn && event.actor === resultRequest.actor &&
            (resultRequest.kind === 'competition' ? event.competition && (!resultRequest.province || event.provinces.includes(resultRequest.province)) : event.war));
        if (outcome) {
            setResult(outcome);
            setResultRequest(null);
            return;
        }
        const waiting = resultRequest.kind === 'competition'
            ? snapshot.game.activity?.actor === resultRequest.actor && snapshot.game.activity?.choice === 'cathedral'
            : snapshot.game.activeWar?.initiator === resultRequest.actor;
        if (!waiting || snapshot.game.turnNumber !== resultRequest.turn) setResultRequest(null);
    }, [resultRequest, snapshot]);
    async function submit(command: string) {
        if (snapshot.viewerId === null) return false;
        setResult(null);
        const [verb, first, second] = command.trim().split(/\s+/);
        const province = verb === 'attack' ? first : verb === 'place' ? second : undefined;
        const competition = verb === 'attack' || (verb === 'place' && snapshot.game.expansionOffers.some(offer => offer.destination === province && offer.primaryAction === 'ATTACK')) ||
            (snapshot.game.activity?.choice === 'cathedral' && ['cathedral', 'pass'].includes(verb));
        const war = ['play', 'pass'].includes(verb) && (
            (snapshot.game.activeWar && [snapshot.game.activeWar.initiator, snapshot.game.activeWar.opponent].includes(snapshot.viewerId)) ||
            (verb === 'play' && snapshot.game.hand.some(card => card.reference === first && card.definition.id === 'war')));
        const request: ResultRequest | null = competition || war ? {
            revision: snapshot.revision, ids: new Set(snapshot.events.map(event => event.id)), accepted: false,
            turn: snapshot.game.turnNumber, kind: competition ? 'competition' : 'war', province,
            actor: competition ? snapshot.game.activity?.actor ?? snapshot.viewerId : snapshot.game.activeWar?.initiator ?? snapshot.viewerId,
        } : null;
        setResultRequest(request);
        const accepted = await sendCommand(command);
        setResultRequest(accepted && request ? {...request, accepted: true} : null);
        return accepted;
    }
    return {result: visibleResult, dismiss: () => setResult(null), submit};
}
