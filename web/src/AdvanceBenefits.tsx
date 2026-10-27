import type {Snapshot} from './api';
import {t} from './messages';

/** Reference copy follows the configured rules; all prices and effects remain engine projections. */
export function AdvanceBenefits({id, snapshot}: {id: string; snapshot: Snapshot}) {
    const rules = snapshot.game.rules;
    const variant = id === 'ENLIGHTENMENT' && rules.miseryRelief === 'CATEGORY_COMPLETION' ? 'CATEGORY_COMPLETION'
        : id === 'WIND_WATERMILL' ? rules.windWatermill
        : id === 'WRITTEN_RECORD' ? rules.writtenRecord
        : id === 'PATRONAGE' ? rules.patronage : null;
    const key = `advance.${id}.effect${variant ? `.${variant}` : ''}`;
    return <div className="advance-benefits"><p>{t(key)}</p>
        {((id === 'NEW_WORLD' && rules.incomeRelief === 'NEW_WORLD') ||
            (id === 'ENLIGHTENMENT' && rules.incomeRelief === 'ENLIGHTENMENT')) && <p>{t('advance.incomeRelief')}</p>}
        {id === 'MASTER_ART' && rules.immediateMasterArtDiscard && <p>{t('advance.immediateDiscard')}</p>}
    </div>;
}
