import type {Snapshot} from './api';

/** Committed decision boundaries exclude polling revisions and private readiness changes. */
export function decisionIdentity(snapshot: Snapshot): string {
    const {game, gameId, viewerId, prompt} = snapshot;
    const activity = game.activity;
    return JSON.stringify([gameId, viewerId, snapshot.undo?.editRevision, game.turnNumber, game.stage, activity?.actor,
        activity?.decisionPlayer, activity?.choice, activity?.purchaseStep, activity?.decisionEntry,
        prompt?.messageId]);
}

export interface TargetLimit {eligible: string[]; capacity: number}

/** Limits come from authoritative offers; retained invalid targets stay visible for correction. */
export function targetSelection(selected: string[], eligible: string[], required: number,
    limits: TargetLimit[] = []) {
    const invalid = selected.some(id => !eligible.includes(id)) || selected.length > required ||
        limits.some(limit => selected.filter(id => limit.eligible.includes(id)).length > limit.capacity);
    const canPick = (id: string, current = selected) => current.includes(id) ||
        (eligible.includes(id) && current.length < required && limits.every(limit =>
            !limit.eligible.includes(id) || current.filter(target => limit.eligible.includes(target)).length < limit.capacity));
    return {invalid, valid: !invalid && selected.length === required, canPick,
        toggle: (id: string, current: string[]) => current.includes(id) ? current.filter(target => target !== id)
            : canPick(id, current) ? [...current, id] : current};
}
