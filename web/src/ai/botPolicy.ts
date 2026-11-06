import type {BotJob} from '../api';

type PolicyInput = Pick<BotJob, 'observation' | 'purchase' | 'difficulty' | 'seed'>;
type Selector = {choose: (observation: string, purchase: string | null, difficulty: string, seed: number) => string};
type PolicyModule = {
    chooseBotMove: (observation: string, model: string, difficulty: string, seed: number) => string;
    PurchaseBotSelector?: new (baselineModel: string, purchaseModel: string) => Selector;
};

function optionalJson(value: unknown): string | null {
    if (value == null) return null;
    try {return JSON.stringify(value) ?? null;}
    catch {return null;}
}

/** The immutable actor and its compiled index maps are constructed once for each worker lifetime. */
export function createBotPolicyRunner(load: () => Promise<PolicyModule>, baselineModel: unknown, purchaseModel: unknown = null) {
    const baselineJson = JSON.stringify(baselineModel);
    let purchaseJson = optionalJson(purchaseModel) ?? '';
    let loaded: Promise<{module: PolicyModule; selector?: Selector}> | undefined;
    return async (job: PolicyInput): Promise<string> => {
        loaded ??= load().then(module => {
            try {
                const selector = module.PurchaseBotSelector && new module.PurchaseBotSelector(baselineJson, purchaseJson);
                return {module, selector};
            } catch {
                return {module};
            } finally {
                purchaseJson = '';
            }
        });
        const {module, selector} = await loaded;
        const observation = JSON.stringify(job.observation);
        if (selector) {
            try {return selector.choose(observation, optionalJson(job.purchase), job.difficulty, job.seed);}
            catch { /* Optional actor failures retain the ordinary policy for this decision. */ }
        }
        return module.chooseBotMove(observation, baselineJson, job.difficulty, job.seed);
    };
}
