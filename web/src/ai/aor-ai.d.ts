declare module 'aor-ai' {
    export function chooseBotMove(observationJson: string, modelJson: string, difficulty: string, seed: number): string;
    export class PurchaseBotSelector {
        constructor(baselineModelJson: string, purchaseModelJson: string);
        choose(observationJson: string, purchaseJson: string | null, difficulty: string, seed: number): string;
    }
}
