import {ChoiceButtons} from './DecisionControls';
import {t} from './messages';
import {HelpLink} from './HelpLink';

import ruleOptions from './ruleOptions.json';

/** Generated from RuleOptionView; exportRuleOptions and CI enforce the domain source of truth. */
export const defaultVariants: Record<string, string> = Object.fromEntries(ruleOptions.map(option => [option.key, option.value]));
export function validVariants(value: unknown): value is Record<string, string> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
    const options = value as Record<string, unknown>;
    return Object.keys(options).length === ruleOptions.length && ruleOptions.every(option =>
        option.choices.includes(String(options[option.key])));
}

/** Creation records these choices in the same transaction as player registration. */
export function Variants({value, onChange, disabled}: {
    value: Record<string, string>; onChange: (options: Record<string, string>) => void; disabled: boolean;
}) {
    return <fieldset className="creation-variants"><legend>{t('create.variants')}</legend>
        {ruleOptions.map(option => <div className="rule-option" key={option.key}>
            <p id={`variant-label-${option.key}`}>{t(`option.${option.key}`)} <HelpLink topic={`variant-${option.key}`} label={t(`option.${option.key}`)}/></p>
            <ChoiceButtons label={t(`option.${option.key}`)} id={`variant-${option.key}`} value={value[option.key]} disabled={disabled}
                onChange={choice => onChange({...value, [option.key]: choice})}
                choices={option.choices.map(choice => ({value: choice, label: t(`optionValue.${choice}`)}))}/>
        </div>)}
    </fieldset>;
}
