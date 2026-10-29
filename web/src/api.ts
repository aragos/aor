export interface IncomeView {
    ordinary: number; baseIncome: number; middleClassBonus: number; interestProfit: number; reliefSpaces: number;
}
export interface ExpansionOfferView {
    destination: string; primaryAction: 'PLACE' | 'ATTACK' | 'INSPECT'; requiredTokens: number;
    availablePool: number; resultingPool: number | null; establishesMarker: boolean; uncertain: boolean;
    blockers: string[]; partialPlacements: {tokens: number; resultingPool: number}[];
    shipping: {used: number; capacity: number | null; required: number} | null;
    incomeBefore: number | null; incomeAfter: number | null;
    reachable: boolean;
}
export interface CommodityPayoutView {
    commodity: string; adjustments: ('SHORTAGE' | 'SURPLUS')[];
    owners: {player: string; provinces: string[]; marketCount: number; effectiveBox: number; payout: number; industryBonus: number}[];
}
export interface CardChoiceView {
    kind: 'NONE' | 'COMMODITY' | 'PLAYER' | 'PROVINCE' | 'PROVINCES' | 'AREA' | 'CATEGORY' | 'PROTECTION';
    value: string | null; command: string | null;
}
export interface CardOfferView {
    reference: string; playable: boolean; blockers: string[]; choices: CardChoiceView[];
    commodities: string[];
    leaderDiscounts: LeaderDiscountView[];
    provinceTargets: string[]; requiredTargetCount: number | null; protectionMax: number | null;
}
export interface LeaderDiscountView {
    advance: string; printedAmount: number; conditionalAmount: number; writtenRecordBonus: number;
    effectiveDiscount: number; condition: string | null;
}
export interface WarOutcomeView {
    initiator: string; opponent: string; totals: Record<string, number>; winner: string | null;
    requiredTransfers: number;
}
export interface TokenPurchaseOfferView {
    amount: number; cost: number; tokensReceived: number; cashAfter: number; stockAfter: number; poolAfter: number;
}
export interface ShippingOfferView {
    cost: number; ships: PlayerView['ships'] | null; cashAfter: number | null; blockers: string[];
}
export interface PlayerView {
    id: string; name: string; cash: number; capital: string | null; handCount: number;
    stockTokens: number; poolTokens: number; boardMarkers: number; stockMarkers: number; misery: number | null;
    income: IncomeView | null;
    poolAvailable: boolean;
    ships: {fleet: 'NONE' | 'GALLEY' | 'SEAWORTHY' | 'OCEAN'; level: number}; advances: string[];
}
export interface ProvinceDefinition {
    id: string; area: string | null; overseas: string | null; marketNumber: number;
    commodities: string[]; capital: string | null; coastal: boolean; access: string[]; satellite: boolean;
}
export interface AdvanceDefinition {
    id: string; letter: string; category: string; cost: number; credit: number; miseryRelief: number;
    prerequisites: string[]; messageId: string; purchaseMisery: number;
}
export interface PublicCatalog {
    provinces: ProvinceDefinition[]; advances: AdvanceDefinition[];
    commodities: {id: string; baseValue: number; diceTotal: number; ownershipTrackBoxes: number; printedPayouts: number[]}[];
    landBorders: {first: string; second: string}[]; coastalConnections: {first: string; second: string}[];
    satelliteSupports: {satellite: string; market: string}[];
    seaCoasts: {sea: string; province: string}[];
    seaAreas: Record<string, string>;
    miseryTrack: (number | null)[];
    shippingTrack: PlayerView['ships'][];
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
    leaders: {leader: string; owner: string; role: 'DISPLAY' | 'EXPOSED'; displayBox: number | null; protectionCash: number; patronageClaimants: string[]}[];
    enlightenedRulers: string[]; military: {player: string; event: string}[];
    papalCategory: string | null; crusadesPlayed: boolean;
}
export interface CardDefinition {
    id: string; kind: 'commodity' | 'event' | 'leader'; messageId: string; shape: 'CIRCLE' | 'SQUARE';
}
export type RebuiltMarkerBonus = 'EXCLUDE' | 'INCLUDE';
export interface HistoryEventView {
    participants?: {id: string; name: string; capital: string | null}[];
    id: string; kind: 'START' | 'BID_CAPITAL' | 'CHOOSE_CAPITAL' | 'BUY_TOKENS' | 'CHOOSE_TURN_POSITION' |
        'RETURN_CARD' | 'PASS' | 'SWAP_ORDER' | 'BUY_CARD' | 'DISCARD_CARD' | 'CANCEL_ADJUSTMENT' |
        'UPGRADE_SHIPS' | 'BUY_ADVANCE' | 'STABILIZE' | 'RELIEVE_MISERY' | 'PLACE' | 'ESTABLISH_MARKER' |
        'ATTACK' | 'COMPETITION_RESULT' | 'WAR_RESULT' | 'REDUCE_MARKER' | 'BUY_EXPANSION_CARD' | 'CATHEDRAL' | 'WINDMILL' | 'WRITTEN_RECORD' |
        'OFFER_CARD' | 'PLAY_CARD' | 'ADJUST_MARKET' | 'CIVIL_WAR_LOSS' | 'REPLACE_EVENT_TOKEN' |
        'CEDE_WAR' | 'FORGO_MARKER' | 'MOVE_MARKER' | 'PATRONIZE' | 'DRAW_CARD' | 'TRADE_CARD' | 'ROLL' |
        'NEXT_EPOCH' | 'ENDING_TRIGGERED' | 'GAME_FINISHED' | 'INCOME' | 'HOLY_INDULGENCE';
    actor: string | null; target: string | null; turnNumber: number | null; stage: string;
    provinces: string[]; card: string | null; commodity: string | null; advance: string | null;
    amount: number | null; detail: string | null;
    dice: {player: string | null; value: number; color: 'WHITE' | 'BLACK' | 'GREEN'}[];
    cashChanges: {player: string; amount: number}[];
    drawReason: 'INITIAL_DEAL' | 'TURN_DRAW' | 'URBAN_ASCENDANCY' | 'EXPANSION_PURCHASE' | 'EXPANSION_BONUS' | 'SHORTAGE' | null;
    protectionCash: number | null; leaderRebate: number | null;
    marketRoll: {total: number; commodities: string[]; adjustment: 'SHORTAGE' | 'SURPLUS'; cancelled: string[]} | null;
    competition: {attackerWon: boolean; defenders: string[]} | null;
    war: WarOutcomeView | null;
}
export interface TokenPurchaseQuoteResponse {revision: number; quote: TokenPurchaseOfferView}
export interface Snapshot {
    gameId: string; revision: number; viewerId: string; creatorId: string;
    status?: 'ACTIVE' | 'CANCELLED' | 'ABANDONED'; accountId?: string | null;
    game: {stage: string; currentEpoch: number;
        rules: {patronage: 'PROTECTION_MONEY' | 'THREE_BOX_DISPLAY'; [key: string]: string | boolean};
        ruleOptions: {key: string; value: string; choices: string[]}[]; rebuiltMarkerBonus: RebuiltMarkerBonus; players: PlayerView[];
        hand: {reference: string; definition: CardDefinition}[];
        capitalOrder: string[]; revealedBids: Record<string, number>;
        turnNumber: number | null; turnOrder: string[]; orderBoxes: Record<string, number>;
        revealedTokenPurchases: Record<string, number>; provisionalTieOrder: string[];
        board: ProvinceView[]; catalog: PublicCatalog | null; deckRemaining: number; endingTriggered: boolean;
        scores: ScoreView[]; adjustments: Record<string, ('SHORTAGE' | 'SURPLUS')[]>;
        effects: CardTurnEffects; activeWar: {initiator: string; opponent: string; startedTurn: number; ties: number} | null;
        playedLeaderDiscounts: {leader: string; usable: boolean; discounts: LeaderDiscountView[]}[];
        unplayedAdvanceLeaders?: {advance: string; epochs: {epoch: number; count: number}[]}[];
        pirateTargets: {requiredCount: number; eligibleProvinces: string[]} | null;
        markerPlacement: {player: string; destination: string} | null;
        warCession: {winner: string; requiredCount: number; eligibleProvinces: string[]; overseasCapacity: number} | null;
        activity: {actor: string | null; decisionPlayer: string | null; choice: string | null; purchaseStep: string | null; startEntry: number; decisionEntry: number} | null;
        voids: {armorAndStirrups: boolean; crusades: boolean; papalDecree: boolean};
        advanceOffers: {advance: string; price: number | null; unavailableReason: string | null;
            leaderDiscounts: {leaderCardId: string; baseAmount: number; writtenRecordBonus: number; amount: number}[]}[];
        reliefOffers: {spaces: number; cashCost: number; ratingSpent: number; affordable: boolean}[];
        stabilizationCost: number | null; stabilizationMisery: number | null; burdenReferences: string[];
        lockedProvinces: string[];
        expansionOffers: ExpansionOfferView[]; commodityPayouts: CommodityPayoutView[]; cardOffers: CardOfferView[];
        tokenPurchaseOffers: TokenPurchaseOfferView[]; shippingOffer: ShippingOfferView | null};
    prompt: {messageId: string; players: string[]} | null;
    legalChoices: {commands: string[]; bidMin: number | null; bidMax: number | null};
    submitted: string[]; pendingBid: number | null; pendingReturn: string | null; history: string[];
    events: HistoryEventView[];
    undo: {policy: 'COMPETITIVE' | 'REGULAR_TURN' | 'FRIENDLY_TURN' | 'FRIENDLY_GAME'; available: boolean;
        fromEntry: number | null; entryCount: number; editRevision: number;
        actions: {entry: number; actor: string; text: string}[]};
}
export interface CreatedGame {gameId: string; registered?: boolean; links: {id: string; name: string; path: string}[]}
export interface ApplicationConfig {authMode: 'local' | 'firebase'; firebase: Record<string, string> | null; pollIntervalMs: number}
export interface Account {id: string; handle: string; displayName: string; mayCreateGames: boolean; admin: boolean}
export interface Session {id: string; emailVerified: boolean; account: Account | null}
export type GameStatus = 'ONGOING' | 'MY_MOVE' | 'FINISHED' | 'CANCELLED' | 'ABANDONED' | 'UNAVAILABLE';
export interface GameSummary {id: string; createdAt: number; revision: number; status: GameStatus; stage: string | null; players: {id: string; name: string}[]; myMove: boolean}
export interface GamePage {games: GameSummary[]; nextCursor: string | null}
export class ApiError extends Error {
    constructor(public code: string, public entityIds: string[] = [], public entryIndex: number | null = null) { super(code); }
}
let authentication: (() => Promise<string | undefined>) | undefined;
export function setAuthentication(provider?: () => Promise<string | undefined>) { authentication = provider; }
async function fetchResponse(path: string, token?: string, body?: unknown, etag?: string): Promise<Response> {
    let response: Response;
    try {
        const credential = token ?? await authentication?.();
        response = await fetch(path, {
            method: body === undefined ? 'GET' : 'POST', cache: 'no-store',
            headers: {...(credential ? {Authorization: `Bearer ${credential}`} : {}), ...(etag ? {'If-None-Match': etag} : {}), ...(body === undefined ? {} : {'Content-Type': 'application/json'})},
            ...(body === undefined ? {} : {body: JSON.stringify(body)}),
        });
    } catch { throw new ApiError('NETWORK'); }
    return response;
}
async function responseJson<T>(response: Response): Promise<T> {
    let result;
    try { result = await response.json(); } catch { throw new ApiError('SERVER'); }
    if (!response.ok) throw new ApiError(typeof result?.code === 'string' ? result.code : 'SERVER',
        Array.isArray(result?.entityIds) ? result.entityIds.filter((id: unknown): id is string => typeof id === 'string') : [],
        Number.isInteger(result?.entryIndex) ? result.entryIndex : null);
    return result as T;
}
export async function request<T>(path: string, token?: string, body?: unknown): Promise<T> {
    return responseJson<T>(await fetchResponse(path, token, body));
}
/** A 304 keeps the existing private snapshot without parsing an empty response. */
export async function requestSnapshot(path: string, token?: string, etag?: string) {
    const response = await fetchResponse(path, token, undefined, etag);
    return {etag: response.headers.get('ETag') ?? undefined,
        snapshot: response.status === 304 ? null : await responseJson<Snapshot>(response)};
}
export function errorCode(error: unknown): string { return error instanceof ApiError ? error.code : 'SERVER'; }
