import type {BotJob} from '../api';

type PolicyInput = Pick<BotJob, 'observation' | 'purchase' | 'card' | 'expansion' | 'difficulty' | 'seed'>;
type Selector = {choose: (observation: string, purchase: string | null, difficulty: string, seed: number) => string};
type JointSelector = {choose: (observation: string, purchase: string | null, card: string | null, difficulty: string, seed: number) => string};
type ExpansionSelector = {choose: (observation: string, purchase: string | null, card: string | null, expansion: string | null, difficulty: string, seed: number) => string};
type PolicyModule = {
    chooseBotMove: (observation: string, model: string, difficulty: string, seed: number) => string;
    PurchaseBotSelector?: new (baselineModel: string, purchaseModel: string) => Selector;
    JointBotSelector?: new (baselineModel: string, incumbentPurchaseModel: string, jointModel: string) => JointSelector;
    ExpansionBotSelector?: new (incumbent: JointSelector, expansionModel: string) => ExpansionSelector;
};

function optionalJson(value: unknown): string | null {
    if (value == null) return null;
    try {return JSON.stringify(value) ?? null;}
    catch {return null;}
}

/** Browser-wire identity is UTF-8 of the exact constructor JSON, not a native artifact hash. */
async function expansionReferenceMatches(asset: string, ordinary: string, purchase: string, joint: string): Promise<boolean> {
    if (!asset || asset.length > 26_000_000 || !purchase || !joint) return false;
    try {
        const reference = JSON.parse(asset).reference;
        if (!reference || typeof reference !== 'object' || Array.isArray(reference)
            || Object.keys(reference).sort().join(',') !== 'jointSha256,kind,ordinarySha256,purchaseSha256,supportProfile'
            || reference.kind !== 'browser-policy-reference-1'
            || (reference.supportProfile !== null && reference.supportProfile !== 'known-own-chaos-support-1')) return false;
        const expected = [reference.ordinarySha256, reference.purchaseSha256, reference.jointSha256];
        if (!expected.every(hash => typeof hash === 'string' && /^[0-9a-f]{64}$/.test(hash))) return false;
        const actual = await Promise.all([ordinary, purchase, joint].map(async input => {
            const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
            return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
        }));
        return expected.every((hash, index) => hash === actual[index]);
    } catch {return false;}
}

/** The immutable actor and its compiled index maps are constructed once for each worker lifetime. */
export function createBotPolicyRunner(load: () => Promise<PolicyModule>, baselineModel: unknown, purchaseModel: unknown = null, jointModel: unknown = null, expansionModel: unknown = null) {
    const baselineJson = JSON.stringify(baselineModel);
    let purchaseJson = optionalJson(purchaseModel) ?? '';
    let jointJson = jointModel == null ? '' : optionalJson(jointModel) ?? '';
    let expansionJson = expansionModel == null ? '' : optionalJson(expansionModel) ?? '';
    let loaded: Promise<{module: PolicyModule; selector?: Selector; joint?: JointSelector; expansion?: ExpansionSelector}> | undefined;
    return async (job: PolicyInput): Promise<string> => {
        loaded ??= load().then(async module => {
            let selector: Selector | undefined;
            let joint: JointSelector | undefined;
            let expansion: ExpansionSelector | undefined;
            try {
                selector = module.PurchaseBotSelector && new module.PurchaseBotSelector(baselineJson, purchaseJson);
            } catch { /* Invalid incumbent assets preserve ordinary inference. */ }
            try {
                if (jointJson && module.JointBotSelector) {
                    joint = new module.JointBotSelector(baselineJson, purchaseJson, jointJson);
                }
            } catch { /* An optional joint failure preserves the incumbent purchase actor. */ }
            try {
                if (expansionJson && joint && module.ExpansionBotSelector
                    && await expansionReferenceMatches(expansionJson, baselineJson, purchaseJson, jointJson)) {
                    expansion = new module.ExpansionBotSelector(joint, expansionJson);
                }
            } catch { /* Incompatible optional expansion assets preserve the complete existing selector. */ }
            finally {
                purchaseJson = '';
                jointJson = '';
                expansionJson = '';
            }
            return {module, selector, joint, expansion};
        });
        const {module, selector, joint, expansion} = await loaded;
        const observation = JSON.stringify(job.observation);
        if (expansion) {
            try {return expansion.choose(observation, optionalJson(job.purchase), optionalJson(job.card), optionalJson(job.expansion), job.difficulty, job.seed);}
            catch { /* Keep the original controller if the optional outer boundary fails. */ }
        }
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
