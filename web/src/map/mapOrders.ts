import type {ExpansionOfferView, Snapshot} from '../api';

/** Canonical direct actions for secondary province controls. Expansion uses typed offers. */
export function provinceOrders(snapshot: Snapshot, province: string) {
    if (snapshot.viewerId === null || !snapshot.prompt?.players.includes(snapshot.viewerId)) return [];
    return snapshot.legalChoices.commands.filter(command => {
        const [verb, target] = command.split(' ');
        return ['reduce-marker', 'move-marker', 'choose-capital'].includes(verb) && target === province;
    });
}

/** Prefer a full commitment, falling back to the largest authoritative partial placement. */
export function maximumPartialPlacement(offer: ExpansionOfferView | undefined) {
    if (!offer || offer.primaryAction === 'INSPECT' || offer.resultingPool !== null) return undefined;
    return offer.partialPlacements.reduce<typeof offer.partialPlacements[number] | undefined>(
        (maximum, choice) => !maximum || choice.tokens > maximum.tokens ? choice : maximum, undefined);
}
