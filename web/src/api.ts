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
    id: string; kind: 'START' | 'BID_CAPITAL' | 'CHOOSE_CAPITAL' | 'BUY_TOKENS' | 'CHOOSE_TURN_POSITION' |
        'RETURN_CARD' | 'PASS' | 'SWAP_ORDER' | 'BUY_CARD' | 'DISCARD_CARD' | 'CANCEL_ADJUSTMENT' |
        'UPGRADE_SHIPS' | 'BUY_ADVANCE' | 'STABILIZE' | 'RELIEVE_MISERY' | 'PLACE' | 'ESTABLISH_MARKER' |
        'ATTACK' | 'COMPETITION_RESULT' | 'WAR_RESULT' | 'REDUCE_MARKER' | 'BUY_EXPANSION_CARD' | 'CATHEDRAL' | 'WINDMILL' | 'WRITTEN_RECORD' |
        'OFFER_CARD' | 'PLAY_CARD' | 'ADJUST_MARKET' | 'CIVIL_WAR_LOSS' | 'REPLACE_EVENT_TOKEN' |
        'CEDE_WAR' | 'FORGO_MARKER' | 'MOVE_MARKER' | 'PATRONIZE' | 'DRAW_CARD' | 'TRADE_CARD' | 'ROLL';
    actor: string | null; target: string | null; turnNumber: number | null; stage: string;
    provinces: string[]; card: string | null; commodity: string | null; advance: string | null;
    amount: number | null; detail: string | null; dice: {player: string | null; value: number}[];
    competition: {attackerWon: boolean; defenders: string[]} | null;
    war: WarOutcomeView | null;
}
export interface TokenPurchaseQuoteResponse {revision: number; quote: TokenPurchaseOfferView}
export interface Snapshot {
    gameId: string; revision: number; viewerId: string; creatorId: string;
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
        pirateTargets: {requiredCount: number; eligibleProvinces: string[]} | null;
        markerPlacement: {player: string; destination: string} | null;
        warCession: {winner: string; requiredCount: number; eligibleProvinces: string[]; overseasCapacity: number} | null;
        activity: {actor: string | null; decisionPlayer: string | null; choice: string | null; purchaseStep: string | null; startEntry: number} | null;
        voids: {armorAndStirrups: boolean; crusades: boolean; papalDecree: boolean};
        advanceOffers: {advance: string; price: number | null; unavailableReason: string | null;
            leaderDiscounts: {leaderCardId: string; baseAmount: number; writtenRecordBonus: number; amount: number}[]}[];
        reliefOffers: {spaces: number; cashCost: number; ratingSpent: number; affordable: boolean}[];
        stabilizationCost: number | null; burdenReferences: string[];
        lockedProvinces: string[];
        expansionOffers: ExpansionOfferView[]; commodityPayouts: CommodityPayoutView[]; cardOffers: CardOfferView[];
        tokenPurchaseOffers: TokenPurchaseOfferView[]; shippingOffer: ShippingOfferView | null};
    prompt: {messageId: string; players: string[]} | null;
    legalChoices: {commands: string[]; bidMin: number | null; bidMax: number | null};
    submitted: string[]; pendingBid: number | null; pendingReturn: string | null; history: string[];
    events: HistoryEventView[];
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
