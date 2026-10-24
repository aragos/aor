export interface PlayerView {
    id: string; name: string; cash: number; capital: string | null; handCount: number;
    stockTokens: number; poolTokens: number; boardMarkers: number; stockMarkers: number; misery: number | null;
    ships: {fleet: 'NONE' | 'GALLEY' | 'SEAWORTHY' | 'OCEAN'; level: number}; advances: string[];
}
export interface ProvinceDefinition {
    id: string; area: string | null; overseas: string | null; marketNumber: number;
    commodities: string[]; capital: string | null; coastal: boolean; access: string[]; satellite: boolean;
}
export interface AdvanceDefinition {
    id: string; category: string; cost: number; credit: number; miseryRelief: number;
    prerequisites: string[]; messageId: string; purchaseMisery: number;
}
export interface PublicCatalog {
    provinces: ProvinceDefinition[]; advances: AdvanceDefinition[];
    commodities: {id: string; baseValue: number; diceTotal: number; ownershipTrackBoxes: number; printedPayouts: number[]}[];
    landBorders: {first: string; second: string}[]; coastalConnections: {first: string; second: string}[];
    satelliteSupports: {satellite: string; market: string}[];
}
export interface ProvinceView {
    id: string; marker: {owner: string; side: 'COLOURED' | 'WHITE'} | null;
    tokens: {owner: string; coloured: number; white: number}[];
}
export interface ScoreView {
    player: string; advancePoints: number; cash: number; misery: number | null;
    total: number | null; winner: boolean;
}
export interface CardTurnEffects {
    leaders: {leader: string; owner: string; role: 'DISPLAY' | 'EXPOSED'; protectionCash: number; patronageClaimants: string[]}[];
    enlightenedRulers: string[]; military: {player: string; event: string}[];
    papalCategory: string | null; crusadesPlayed: boolean;
}
export interface CardDefinition {
    id: string; kind: 'commodity' | 'event' | 'leader'; messageId: string; shape: 'CIRCLE' | 'SQUARE';
}
export type RebuiltMarkerBonus = 'EXCLUDE' | 'INCLUDE';
export interface Snapshot {
    gameId: string; revision: number; viewerId: string; creatorId: string;
    game: {stage: string; epochs: number; rebuiltMarkerBonus: RebuiltMarkerBonus; players: PlayerView[];
        hand: {reference: string; definition: CardDefinition}[];
        capitalOrder: string[]; revealedBids: Record<string, number>;
        turnNumber: number | null; turnOrder: string[]; orderBoxes: Record<string, number>;
        revealedTokenPurchases: Record<string, number>; provisionalTieOrder: string[];
        board: ProvinceView[]; catalog: PublicCatalog | null; deckRemaining: number; endingTriggered: boolean;
        scores: ScoreView[]; adjustments: Record<string, ('SHORTAGE' | 'SURPLUS')[]>;
        effects: CardTurnEffects; activeWar: {initiator: string; opponent: string; startedTurn: number; ties: number} | null;
        warCession: {winner: string; requiredCount: number; eligibleProvinces: string[]; overseasCapacity: number} | null;
        activity: {actor: string | null; decisionPlayer: string | null; choice: string | null; purchaseStep: string | null; startEntry: number} | null;
        voids: {armorAndStirrups: boolean; crusades: boolean; papalDecree: boolean};
        advanceOffers: {advance: string; price: number | null; unavailableReason: string | null}[];
        reliefOffers: {spaces: number; cashCost: number; ratingSpent: number; affordable: boolean}[];
        stabilizationCost: number | null; burdenReferences: string[]};
    prompt: {messageId: string; players: string[]} | null;
    legalChoices: {commands: string[]; bidMin: number | null; bidMax: number | null};
    submitted: string[]; pendingBid: number | null; history: string[];
}
export interface CreatedGame {gameId: string; links: {id: string; name: string; path: string}[]}
export class ApiError extends Error {
    constructor(public code: string) { super(code); }
}
export async function request<T>(path: string, token?: string, body?: unknown): Promise<T> {
    let response: Response;
    try {
        response = await fetch(path, {
            method: body === undefined ? 'GET' : 'POST', cache: 'no-store',
            headers: {...(token ? {Authorization: `Bearer ${token}`} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
            ...(body === undefined ? {} : {body: JSON.stringify(body)}),
        });
    } catch { throw new ApiError('NETWORK'); }
    let result;
    try { result = await response.json(); } catch { throw new ApiError('SERVER'); }
    if (!response.ok) throw new ApiError(typeof result.code === 'string' ? result.code : 'SERVER');
    return result as T;
}
export function errorCode(error: unknown): string { return error instanceof ApiError ? error.code : 'SERVER'; }
