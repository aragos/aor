import {ChoiceButtons} from './DecisionControls';
import {t} from './messages';

export const defaultVariants: Record<string, string> = {
    'deck-modification': 'disabled', 'initial-card': 'any',
    'immediate-master-art-discard': 'disabled', map: 'standard',
};

/** Creation records these choices in the same transaction as player registration. */
export function Variants({value, onChange, disabled}: {
    value: Record<string, string>; onChange: (options: Record<string, string>) => void; disabled: boolean;
}) {
    return <fieldset className="creation-variants"><legend>{t('create.variants')}</legend>
        {Object.keys(defaultVariants).filter(key => key !== 'map').map(key => <div className="rule-option" key={key}>
            <p id={`variant-label-${key}`}>{t(`option.${key}`)}</p>
            <ChoiceButtons id={`variant-${key}`} value={value[key]} disabled={disabled}
                onChange={choice => onChange({...value, [key]: choice})}
                choices={(key === 'initial-card' ? ['any', 'no-crusades', 'no-events', 'commodity-only'] : ['disabled', 'enabled'])
                    .map(choice => ({value: choice, label: t(`optionValue.${choice}`)}))}/>
        </div>)}
        <p>{t('option.map')}: {t('optionValue.standard')}</p>
        <button type="button" className="secondary" disabled title={t('create.europeanPending')}>{t('optionValue.european')}</button>
        <p className="muted small">{t('create.europeanPending')}</p>
    </fieldset>;
}
