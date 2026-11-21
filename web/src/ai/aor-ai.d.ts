declare module 'aor-ai' {
    export function chooseBotMove(observationJson: string, modelJson: string, difficulty: string, seed: number): string;
    export class HardBotSelector {
        constructor(modelJson: string, playerCount: number);
        choose(observationJson: string, initiativeJson: string | null, finishJson: string | null, seed: number): string;
    }
}
