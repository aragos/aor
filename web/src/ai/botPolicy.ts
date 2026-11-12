import type {BotJob} from '../api';

type PolicyInput = Pick<BotJob, 'observation' | 'purchase' | 'card' | 'difficulty' | 'seed'>;
type Selector = {choose: (observation: string, purchase: string | null, difficulty: string, seed: number) => string};
type JointSelector = {choose: (observation: string, purchase: string | null, card: string | null, difficulty: string, seed: number) => string};
type PolicyModule = {
    chooseBotMove: (observation: string, model: string, difficulty: string, seed: number) => string;
    PurchaseBotSelector?: new (baselineModel: string, purchaseModel: string) => Selector;
    JointBotSelector?: new (baselineModel: string, incumbentPurchaseModel: string, jointModel: string) => JointSelector;
};

function optionalJson(value: unknown): string | null {
    if (value == null) return null;
    try {return JSON.stringify(value) ?? null;}
    catch {return null;}
}

/** The immutable actor and its compiled index maps are constructed once for each worker lifetime. */
export function createBotPolicyRunner(load: () => Promise<PolicyModule>, baselineModel: unknown, purchaseModel: unknown = null, jointModel: unknown = null) {
    const baselineJson = JSON.stringify(baselineModel);
    let purchaseJson = optionalJson(purchaseModel) ?? '';
    let jointJson = jointModel == null ? '' : optionalJson(jointModel) ?? '';
    let loaded: Promise<{module: PolicyModule; selector?: Selector; joint?: JointSelector}> | undefined;
    return async (job: PolicyInput): Promise<string> => {
        loaded ??= load().then(module => {
            let selector: Selector | undefined;
            let joint: JointSelector | undefined;
            try {
                selector = module.PurchaseBotSelector && new module.PurchaseBotSelector(baselineJson, purchaseJson);
            } catch { /* Invalid incumbent assets preserve ordinary inference. */ }
            try {
                if (jointJson && module.JointBotSelector) {
                    joint = new module.JointBotSelector(baselineJson, purchaseJson, jointJson);
                }
            } catch { /* An optional joint failure preserves the incumbent purchase actor. */ }
            finally {
                purchaseJson = '';
                jointJson = '';
            }
            return {module, selector, joint};
        });
        const {module, selector, joint} = await loaded;
        const observation = JSON.stringify(job.observation);
        if (joint) {
            try {return joint.choose(observation, optionalJson(job.purchase), optionalJson(job.card), job.difficulty, job.seed);}
            catch { /* Retain the complete incumbent policy when optional joint inference fails. */ }
        }
        if (selector) {
            try {return selector.choose(observation, optionalJson(job.purchase), job.difficulty, job.seed);}
            catch { /* Optional actor failures retain the ordinary policy for this decision. */ }
        }
        return module.chooseBotMove(observation, baselineJson, job.difficulty, job.seed);
    };
}
