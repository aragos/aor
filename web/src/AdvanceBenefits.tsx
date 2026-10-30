import type {Snapshot} from './api';
import {t} from './messages';

/** Static rules reference; live prices and effects remain engine projections. */
export function AdvanceBenefits({id, rules}: {id: string; rules: Snapshot['game']['rules']}) {
    const key = `advance.${id}.effect`;
    return <div className="advance-benefits"><p>{t(key)}</p>
        {id === 'NEW_WORLD' && <p>{t('advance.incomeRelief')}</p>}
        {id === 'MASTER_ART' && rules.immediateMasterArtDiscard && <p>{t('advance.immediateDiscard')}</p>}
    </div>;
}
