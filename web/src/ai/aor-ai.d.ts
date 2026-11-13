declare module 'aor-ai' {
    export function chooseBotMove(observationJson: string, modelJson: string, difficulty: string, seed: number): string;
    export class PurchaseBotSelector {
        constructor(baselineModelJson: string, purchaseModelJson: string);
        choose(observationJson: string, purchaseJson: string | null, difficulty: string, seed: number): string;
    }
    export class JointBotSelector {
        constructor(baselineModelJson: string, incumbentPurchaseModelJson: string, jointModelJson: string);
        choose(observationJson: string, purchaseJson: string | null, cardJson: string | null, difficulty: string, seed: number): string;
    }
    export class ExpansionBotSelector {
        constructor(incumbent: JointBotSelector, expansionModelJson: string);
        choose(observationJson: string, purchaseJson: string | null, cardJson: string | null, expansionJson: string | null, difficulty: string, seed: number): string;
    }
}
