import type {Snapshot} from '../api';

/** Canonical direct actions for secondary province controls. Expansion uses typed offers. */
export function provinceOrders(snapshot: Snapshot, province: string) {
    if (!snapshot.prompt?.players.includes(snapshot.viewerId)) return [];
    return snapshot.legalChoices.commands.filter(command => {
        const [verb, target] = command.split(' ');
        return ['reduce-marker', 'move-marker', 'choose-capital'].includes(verb) && target === province;
    });
}
