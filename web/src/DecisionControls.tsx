import {useState} from 'react';
import type {ReactNode} from 'react';
import {targetSelection} from './decision';
import type {TargetLimit} from './decision';
import {t} from './messages';

export function useTargets(eligible: string[], required: number, limits: TargetLimit[] = []) {
    const [selected, setSelected] = useState<string[]>([]);
    const policy = targetSelection(selected, eligible, required, limits);
    return {...policy, selected, clear: () => setSelected([]),
        pick: (id: string) => setSelected(current => policy.toggle(id, current))};
}

export function InvalidChoice({invalid, clear}: {invalid: boolean; clear?: () => void}) {
    if (!invalid) return null;
    return <><p className="blocker" role="status">{t('table.invalidatedChoice')}</p>
        {clear && <button type="button" className="secondary clear-targets" onClick={clear}>{t('table.clearSelection')}</button>}</>;
}

export function ResultNotice({className, dismiss, children}: {
    className: string; dismiss: () => void; children: ReactNode;
}) {
    return <section className={className} role="status" aria-live="polite">
        <button type="button" className="close-detail" aria-label={t('table.close')} onClick={dismiss}>×</button>
        {children}
    </section>;
}
