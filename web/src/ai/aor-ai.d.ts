declare module 'aor-ai' {
    export function chooseBotMove(observationJson: string, modelJson: string, difficulty: string, seed: number): string;
}
