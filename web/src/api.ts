export interface PlayerView {
    id: string; name: string; cash: number; capital: string | null; handCount: number;
    stockTokens: number; poolTokens: number; boardMarkers: number; stockMarkers: number; misery: number | null; ships: number;
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
        revealedTokenPurchases: Record<string, number>; provisionalTieOrder: string[]};
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
