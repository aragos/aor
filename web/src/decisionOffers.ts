import type {Snapshot} from './api';

export type DecisionSubject = {kind: 'card' | 'leader' | 'player' | 'commodity' | 'province' | 'loss'; id: string};
export interface FiniteDecision {
    kind: 'discard' | 'trade' | 'patronage' | 'order-swap' | 'cancellation' | 'secondary';
    subject: DecisionSubject;
    command: string;
}

export const leaderReference = (id: string) => id.toLowerCase().replace(/_/g, '-');

/** Adapt authoritative finite choices once; components and alternatives select the same command. */
export function finiteDecisions(snapshot: Snapshot): FiniteDecision[] {
    return snapshot.legalChoices.commands.flatMap((command): FiniteDecision[] => {
        const [action, subject] = command.split(' ');
        switch (action) {
            case 'discard-card': return [{kind: 'discard', subject: {kind: 'card', id: subject}, command}];
            case 'offer-card': return [{kind: 'trade', subject: {kind: 'card', id: subject}, command}];
            case 'patronize': return [{kind: 'patronage', subject: {kind: 'leader', id: subject}, command}];
            case 'swap-order': return [{kind: 'order-swap', subject: {kind: 'player', id: subject}, command}];
            case 'cancel-adjustment': return [{kind: 'cancellation', subject: {kind: 'commodity', id: subject.toUpperCase()}, command}];
            case 'reduce-marker': return [{kind: 'secondary', subject: {kind: 'province', id: subject}, command}];
            case 'civil-war-loss': return [{kind: 'secondary', subject: {kind: 'loss', id: subject}, command}];
            default: return [];
        }
    });
}
