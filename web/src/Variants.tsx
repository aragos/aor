import {ChoiceButtons} from './DecisionControls';
import {t} from './messages';
import {HelpLink} from './HelpLink';

export const defaultVariants: Record<string, string> = {
    'deck-modification': 'disabled', 'initial-card': 'any',
    'immediate-master-art-discard': 'disabled', map: 'standard',
};
export function validVariants(value: unknown): value is Record<string, string> {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return false;
    const options = value as Record<string, unknown>;
    return Object.keys(options).length === 4 && ['standard', 'european'].includes(String(options.map)) &&
        ['any', 'no-crusades', 'no-events', 'commodity-only'].includes(String(options['initial-card'])) &&
        ['deck-modification', 'immediate-master-art-discard'].every(key => ['enabled', 'disabled'].includes(String(options[key])));
}

/** Creation records these choices in the same transaction as player registration. */
export function Variants({value, onChange, disabled}: {
    value: Record<string, string>; onChange: (options: Record<string, string>) => void; disabled: boolean;
}) {
    return <fieldset className="creation-variants"><legend>{t('create.variants')}</legend>
        {Object.keys(defaultVariants).filter(key => key !== 'map').map(key => <div className="rule-option" key={key}>
            <p id={`variant-label-${key}`}>{t(`option.${key}`)} <HelpLink topic={`variant-${key}`} label={t(`option.${key}`)}/></p>
            <ChoiceButtons id={`variant-${key}`} value={value[key]} disabled={disabled}
                onChange={choice => onChange({...value, [key]: choice})}
                choices={(key === 'initial-card' ? ['any', 'no-crusades', 'no-events', 'commodity-only'] : ['disabled', 'enabled'])
                    .map(choice => ({value: choice, label: t(`optionValue.${choice}`)}))}/>
        </div>)}
        <div className="rule-option">
            <p id="variant-label-map">{t('option.map')} <HelpLink topic="variant-map" label={t('option.map')}/></p>
            <ChoiceButtons id="variant-map" value={value.map} disabled={disabled}
                onChange={map => onChange({...value, map})}
                choices={['standard', 'european'].map(map => ({value: map, label: t(`optionValue.${map}`)}))}/>
        </div>
    </fieldset>;
}
