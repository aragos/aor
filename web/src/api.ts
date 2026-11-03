export interface IncomeView {
    ordinary: number; baseIncome: number; middleClassBonus: number; interestProfit: number; reliefSpaces: number;
}
export interface ExpansionOfferView {
    destination: string; primaryAction: 'PLACE' | 'ATTACK' | 'INSPECT'; requiredTokens: number;
    availablePool: number; resultingPool: number | null; establishesMarker: boolean; uncertain: boolean;
    blockers: string[]; partialPlacements: {tokens: number; resultingPool: number}[];
    missingAccess?: string[];
    greenDieWins?: number[] | null;
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
    miseryChanges?: MiseryChangeView[]; miseryUncertainPlayers?: string[];
    cashChanges?: {player: string; before: number; after: number}[];
}
export interface MiseryChangeView {player: string; fromValue: number | null; toValue: number | null}
export interface AppliedLeaderDiscount {
    leaderCardId: string; baseAmount: number; writtenRecordBonus: number; amount: number;
}
export interface AdvanceQuote {
    advance: string; baseCost: number; categoryCredit: number; institutionalResearchCredit: number;
    advanceCredits: {advance: string; amount: number}[]; effectiveDiscount: number;
    leaders: AppliedLeaderDiscount[]; price: number;
    miseryBefore: number | null; miseryAfter: number | null;
}
export interface AdvanceOfferView {
    advance: string; price: number | null; unavailableReason: string | null;
    leaderDiscounts: AppliedLeaderDiscount[];
    priceBreakdown?: AdvanceQuote | null; miseryChanges?: MiseryChangeView[];
}
export interface CardOfferView {
    reference: string; playable: boolean; blockers: string[]; choices: CardChoiceView[];
    commodities: string[];
    leaderDiscounts: LeaderDiscountView[];
    provinceTargets: string[]; requiredTargetCount: number | null; protectionMax: number | null;
    miseryChanges?: MiseryChangeView[]; miseryUncertainPlayers?: string[];
}
export interface LeaderDiscountView {
    advance: string; printedAmount: number; conditionalAmount: number; writtenRecordBonus: number;
    effectiveDiscount: number; condition: string | null;
}
export interface CardInspectionView {
    definition: CardDefinition; commodities: string[]; leaderDiscounts: LeaderDiscountView[];
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
    id: string; name: string; cash: number; writtenCash: number; capital: string | null; handCount: number;
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
export interface CommodityCardCountView {
    commodity: string; epochs: number[]; unplayed: number | null;
}
export interface PublicCatalog {
    dataVersion: string;
    provinces: ProvinceDefinition[]; advances: AdvanceDefinition[];
    commodityCards: CommodityCardCountView[];
    commodities: {id: string; baseValue: number; diceTotal: number; ownershipTrackBoxes: number; printedPayouts: number[]}[];
    landBorders: {first: string; second: string}[]; coastalConnections: {first: string; second: string}[];
    satelliteSupports: {satellite: string; market: string}[];
    seaCoasts: {sea: string; province: string}[];
    seaAreas: Record<string, string>;
    miseryTrack: (number | null)[];
    incomeTrack: number[];
    orderTrack: {number: number; greenWins: number[]}[];
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
    leaders: {leader: string; owner: string; role: 'DISPLAY' | 'EXPOSED'; protectionCash: number; patronageClaimants: string[]}[];
    enlightenedRulers: string[]; military: {player: string; event: string}[];
    papalCategory: string | null; crusadesPlayed: boolean;
}
export interface CardDefinition {
    id: string; kind: 'commodity' | 'event' | 'leader'; messageId: string; shape: 'CIRCLE' | 'SQUARE';
}
export type RebuiltMarkerBonus = 'EXCLUDE' | 'INCLUDE';
export interface HistoryEventView {
    participants?: {id: string; name: string; capital: string | null}[];
    id: string; kind: 'REWIND' | 'ABANDONMENT_PROPOSE' | 'ABANDONMENT_AGREE' | 'ABANDONMENT_DECLINE' | 'ABANDONMENT_WITHDRAW' | 'ABANDONMENT_CLOSE' | 'START' | 'BID_CAPITAL' | 'CHOOSE_CAPITAL' | 'BUY_TOKENS' | 'CHOOSE_TURN_POSITION' |
        'PASS' | 'SWAP_ORDER' | 'BUY_CARD' | 'DISCARD_CARD' | 'CANCEL_ADJUSTMENT' | 'TOKEN_PURCHASES' |
        'UPGRADE_SHIPS' | 'BUY_ADVANCE' | 'STABILIZE' | 'RELIEVE_MISERY' | 'PLACE' | 'ESTABLISH_MARKER' |
        'ATTACK' | 'COMPETITION_RESULT' | 'WAR_RESULT' | 'REDUCE_MARKER' | 'BUY_EXPANSION_CARD' | 'CATHEDRAL' | 'WINDMILL' |
        'OFFER_CARD' | 'PLAY_CARD' | 'CIVIL_WAR_LOSS' | 'REPLACE_EVENT_TOKEN' |
        'CEDE_WAR' | 'FORGO_MARKER' | 'MOVE_MARKER' | 'PATRONIZE' | 'DRAW_CARD' | 'TRADE_CARD' | 'ROLL' |
        'NEXT_EPOCH' | 'ENDING_TRIGGERED' | 'GAME_FINISHED' | 'INCOME' | 'HOLY_INDULGENCE' | 'ORDER_CHANGED';
    actor: string | null; target: string | null; turnNumber: number | null; stage: string;
    provinces: string[]; card: string | null; commodity: string | null; advance: string | null;
    amount: number | null; detail: string | null;
    dice: {player: string | null; value: number; color: 'WHITE' | 'BLACK' | 'GREEN'}[];
    positionChanges?: {player: string; before: number; after: number}[];
    cashChanges: {player: string; amount: number}[];
    miseryChanges: {player: string; spaces: number; fromValue: number | null; toValue: number | null;
        reasons?: {kind: 'ADVANCE_PURCHASE' | 'CATEGORY_COMPLETION' | 'STABILIZATION' | 'PURCHASED_RELIEF' |
            'INCOME_RELIEF' | 'CARD_EFFECT' | 'SURPLUS_DEBT' | 'HOLY_INDULGENCE'; spaces: number | null;
            advance: string | null; card: string | null}[]}[];
    tokenChanges: {player: string; poolDelta: number; stockDelta: number}[];
    tokenPurchases: {player: string; amount: number; cashCost: number; tokensReceived: number}[];
    reliefPayment: {cashCost: number; ratingSpent: number; ratingAvailableBefore: number;
        ratingSources: {advance: string; amount: number}[]} | null;
    drawReason: 'INITIAL_DEAL' | 'TURN_DRAW' | 'URBAN_ASCENDANCY' | 'EXPANSION_PURCHASE' | 'EXPANSION_BONUS' | 'SHORTAGE' | null;
    protectionCash: number | null; leaderRebate: number | null;
    marketRoll: {total: number; commodities: string[]; adjustment: 'SHORTAGE' | 'SURPLUS'; cancelled: string[]} | null;
    competition: {attackerWon: boolean; defenders: string[]} | null;
    war: WarOutcomeView | null;
}
export interface TokenPurchaseQuoteResponse {revision: number; quote: TokenPurchaseOfferView}
export interface PlannedAction {decision: string; command: string}
export type PlanOperation = 'SAVE' | 'ARM' | 'DISARM' | 'DELETE' | 'EXECUTE';
export interface PlanPreview {
    effects: {action: PlannedAction; before: {cash: number; misery: number | null; ships: PlayerView['ships']};
        after: {cash: number; misery: number | null; ships: PlayerView['ships']}; cashChanges: {player: string; before: number; after: number}[];
        advanceQuote: AdvanceQuote | null}[];
    boundary: 'INCOMPLETE' | 'FINISHED' | 'INFORMATION' | 'INVALID';
    diagnostic: {code: string} | null; game: Snapshot['game']; choices: Snapshot['legalChoices'];
    decision: string | null; hypothetical: boolean;
    invalidIndex?: number | null;
}
export interface PlanningQuote {revision: number; preview: PlanPreview; previewToken: string}
export interface PlanningView {
    available: boolean; version: string | null;
    saved: {turnNumber: number; stage: string; actions: PlannedAction[]; armed: boolean; pause: string | null} | null;
    preview: PlanPreview | null; previewToken: string | null; receipts: string[];
    executions: {version: string; fromRevision: number; toRevision: number}[];
    canArm: boolean; canExecute: boolean;
}
export type BotDifficulty = 'EASY' | 'NORMAL' | 'HARD';
export interface BotAutomation {
    players: {id: string; name: string; difficulty: BotDifficulty; policyVersion: string}[];
    canControl: boolean; paused: boolean; epoch: number;
}
export interface BotJob {
    seat: string; difficulty: BotDifficulty; seed: number; decisionId: string;
    submissionVersion: string | null; observation: unknown;
}
export interface BotWork {
    revision: number; epoch: number; leaseUntil: number; paused: boolean;
    observations: BotJob[]; operationReceipts?: string[];
}
export interface Snapshot {
    automation?: BotAutomation | null;
    planning?: PlanningView | null;
    planWriteReceipt?: {operationId: string; version: string} | null;
    name: string;
    gameId: string; revision: number; creatorId: string;
    /** Null for an authenticated spectator receiving only public information. */
    viewerId: string | null;
    status?: 'ACTIVE' | 'CANCELLED' | 'ABANDONED'; accountId?: string | null;
    game: {stage: string; currentEpoch: number;
        rules: {[key: string]: string | boolean};
        ruleOptions: {key: string; value: string; choices: string[]}[]; rebuiltMarkerBonus: RebuiltMarkerBonus; players: PlayerView[];
        hand: {reference: string; definition: CardDefinition}[];
        capitalOrder: string[]; revealedBids: Record<string, number>;
        turnNumber: number | null; turnOrder: string[]; orderBoxes: Record<string, number>;
        revealedTokenPurchases: Record<string, number>; provisionalTieOrder: string[];
        board: ProvinceView[]; catalog: PublicCatalog | null; deckRemaining: number; endingTriggered: boolean;
        scores: ScoreView[]; adjustments: Record<string, ('SHORTAGE' | 'SURPLUS')[]>;
        effects: CardTurnEffects; activeWar: {initiator: string; opponent: string; startedTurn: number; ties: number} | null;
        playedLeaderDiscounts: {leader: string; usable: boolean; discounts: LeaderDiscountView[]}[];
        unplayedAdvanceLeaders?: {advance: string; epochs: {epoch: number; leaders: string[]}[]}[];
        pirateTargets: {requiredCount: number; eligibleProvinces: string[]} | null;
        markerPlacement: {player: string; destination: string} | null;
        replacementToken?: {player: string; provinces: string[]; event: string; poolTokens: number;
            poolCost: number; maxReplacements: number; stockTokens: number; misery: number | null;
            civilWarLossPending: boolean} | null;
        civilWarLoss?: {player: string; cashCost: number; tokenCost: number; cashAfter: number; poolAfter: number} | null;
        warCession: {winner: string; requiredCount: number; eligibleProvinces: string[]; overseasCapacity: number} | null;
        activity: {actor: string | null; decisionPlayer: string | null; choice: string | null; purchaseStep: string | null; startEntry: number; decisionEntry: number} | null;
        voids: {armorAndStirrups: boolean; crusades: boolean; papalDecree: boolean};
        advanceOffers: AdvanceOfferView[];
        patronageOffers?: {command: string; payer: string; recipient: string; cost: number; payerCashAfter: number; recipientCashAfter: number}[];
        adjustmentOffers?: {command: string; commodities: string[]; cost: number; cashAfter: number}[];
        cardPurchaseOffer?: {cost: number; cashAfter: number; blockers: string[]} | null;
        reliefOffers: {spaces: number; cashCost: number; ratingSpent: number; affordable: boolean; cashAfter?: number; miseryBefore?: number | null; miseryAfter?: number | null}[];
        reliefRatingAvailable: number;
        stabilizationCost: number | null; stabilizationMisery: number | null; burdenReferences: string[];
        lockedProvinces: string[];
        markerReductionOffers?: {command: string; province: string; poolBefore: number; poolAfter: number;
            stockBefore: number; stockAfter: number; incomeBefore: number | null; incomeAfter: number | null}[];
        expansionOffers: ExpansionOfferView[]; expansionInspections?: ExpansionOfferView[];
        commodityCards: CommodityCardCountView[];
        commodityPayouts: CommodityPayoutView[]; cardOffers: CardOfferView[];
        cardInspections: CardInspectionView[];
        tokenPurchaseOffers: TokenPurchaseOfferView[]; shippingOffer: ShippingOfferView | null;
        expansionCardCost?: number | null};
    prompt: {messageId: string; players: string[]} | null;
    legalChoices: {commands: string[]; bidMin: number | null; bidMax: number | null};
    abandonment?: {requestId: string; proposer: string; agreed: string[]; outstanding: string[]; operations: string[]} | null;
    abandonmentResult?: 'COMMITTED' | 'ALREADY_AGREED' | null;
    abandonmentReceipt?: string | null; abandonmentReceipts?: string[];
    submissionVersion?: string | null; submissionReceipts?: string[]; submitted: string[]; pendingBid: number | null; history: string[];
    events: HistoryEventView[];
    undo: {policy: 'COMPETITIVE' | 'REGULAR_TURN' | 'FRIENDLY_TURN' | 'FRIENDLY_GAME' | 'LINEAR_GAME'; available: boolean;
        fromEntry: number | null; entryCount: number; editRevision: number;
        actions: {entry: number; actor: string; text: string}[];
        capability?: 'NONE' | 'SUFFIX_EDIT' | 'PREFIX_REWIND'; latestTarget?: RewindTarget | null; converted?: boolean};
}
export interface CreatedGame {gameId: string; name: string; registered?: boolean; links: {id: string; name: string; path: string}[]}
export interface ApplicationConfig {authMode: 'local' | 'firebase'; firebase: Record<string, string> | null; pollIntervalMs: number}
export interface Account {id: string; handle: string; mayCreateGames: boolean; admin: boolean}
export interface Session {id: string; emailVerified: boolean; account: Account | null}
export type GameStatus = 'ONGOING' | 'MY_MOVE' | 'FINISHED' | 'CANCELLED' | 'ABANDONED' | 'UNAVAILABLE';
export interface GameSummary {id: string; name: string; createdAt: number; revision: number; status: GameStatus; stage: string | null; players: {id: string; name: string}[]; myMove: boolean; actionPlayers: string[]; consentResponders?: string[]; consentResponse?: boolean; turnRequired?: boolean}
export interface GamePage {games: GameSummary[]; nextCursor: string | null}
export class ApiError extends Error {
    constructor(public code: string, public entityIds: string[] = [], public entryIndex: number | null = null, public parameters: Record<string, number> = {}, public causeCode: string | null = null) { super(code); }
}
let authentication: (() => Promise<string | undefined>) | undefined;
export function setAuthentication(provider?: () => Promise<string | undefined>) { authentication = provider; }
async function fetchResponse(path: string, token?: string, body?: unknown, etag?: string, signal?: AbortSignal): Promise<Response> {
    let response: Response;
    try {
        const credential = token ?? await authentication?.();
        if (signal?.aborted) throw new ApiError('NETWORK');
        response = await fetch(path, {
            method: body === undefined ? 'GET' : 'POST', cache: 'no-store',
            ...(signal ? {signal} : {}),
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
        [...(Array.isArray(result?.entityIds) ? result.entityIds.filter((id: unknown): id is string => typeof id === 'string') : []),
            ...(Array.isArray(result?.entities) ? result.entities.flatMap((entity: {kind?: unknown; id?: unknown}) => entity && ['PLAYER', 'PROVINCE', 'ADVANCE', 'COMMODITY'].includes(String(entity.kind)) && typeof entity.id === 'string' ? [entity.id] : []) : [])],
        Number.isInteger(result?.entryIndex) ? result.entryIndex : null,
        Object.fromEntries(Object.entries(result?.parameters ?? {}).filter((entry): entry is [string, number] => typeof entry[1] === 'number' && Number.isFinite(entry[1]))),
        typeof result?.diagnostic?.code === 'string' ? result.diagnostic.code : null);
    return result as T;
}
/** A deadline covers credentials, transport and body; abort never proves a write was rejected. */
async function bounded<T>(operation: (signal: AbortSignal) => Promise<T>, signal?: AbortSignal): Promise<T> {
    const controller = new AbortController();
    const cancel = () => controller.abort();
    signal?.addEventListener('abort', cancel, {once: true});
    if (signal?.aborted) cancel();
    const timer = setTimeout(cancel, 15000);
    try {
        const aborted = new Promise<never>((_, reject) => {
            const fail = () => reject(new ApiError('NETWORK'));
            if (controller.signal.aborted) fail();
            else controller.signal.addEventListener('abort', fail, {once: true});
        });
        return await Promise.race([aborted, operation(controller.signal)]);
    } finally {
        clearTimeout(timer);
        signal?.removeEventListener('abort', cancel);
    }
}
export async function request<T>(path: string, token?: string, body?: unknown, signal?: AbortSignal, options: {readOnly?: boolean} = {}): Promise<T> {
    try {
        return await bounded(async current => responseJson<T>(await fetchResponse(path, token, body, undefined, current)), signal);
    } catch (failure) {
        if (body !== undefined && !options.readOnly && failure instanceof ApiError && ['NETWORK', 'SERVER'].includes(failure.code)) {
            throw new ApiError('OUTCOME_UNKNOWN');
        }
        throw failure;
    }
}
/** A 304 retains the authorized view. Obsolete reads cannot publish after cancellation. */
export async function requestSnapshot(path: string, token?: string, etag?: string, signal?: AbortSignal) {
    return bounded(async current => {
        const response = await fetchResponse(path, token, undefined, etag, current);
        return {etag: response.headers.get('ETag') ?? undefined,
            snapshot: response.status === 304 ? null : await responseJson<Snapshot>(response)};
    }, signal);
}
export function errorCode(error: unknown): string { return error instanceof ApiError ? error.code : 'SERVER'; }

export interface RewindTarget {entryCount: number; turnNumber: number | null; stage: string; removesRandomness: boolean; actor: string; actionText: string}
export interface RewindTargets {expectedRevision: number; targets: RewindTarget[]; nextBefore: number | null}
