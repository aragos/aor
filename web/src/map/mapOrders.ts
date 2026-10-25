import type {Snapshot} from '../api';

/** Legal choices project a one-token placement; larger counts remain server-validated. */
export function provinceOrders(snapshot: Snapshot, province: string) {
    if (!snapshot.prompt?.players.includes(snapshot.viewerId)) return [];
    return snapshot.legalChoices.commands.filter(command => {
        const [verb, first, second] = command.split(' ');
        if (verb === 'place') return second === province;
        return ['attack', 'reduce-marker', 'move-marker', 'choose-capital'].includes(verb) && first === province;
    });
}

export function placementOrder(province: string, count: string, pool: number): string | null {
    const amount = Number(count);
    if (!count.trim() || !Number.isSafeInteger(amount) || amount < 1 || amount > pool) return null;
    return `place ${amount} ${province}`;
}
