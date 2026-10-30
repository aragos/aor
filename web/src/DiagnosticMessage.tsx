import type {ApiError, Snapshot} from './api';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';

/** Only recognized viewer-visible entities become reference labels; raw command fragments stay out. */
export function DiagnosticMessage({failure, snapshot}: {failure: ApiError; snapshot: Snapshot}) {
    const names = [...new Set(failure.entityIds.flatMap(id => {
        if (snapshot.game.players.some(player => player.id === id)) return [playerName(snapshot, id)];
        if (snapshot.game.catalog?.provinces.some(province => province.id === id)) return [provinceName(id)];
        const advance = snapshot.game.catalog?.advances.find(advance => advance.id === id || advance.letter === id);
        if (advance) return [advanceName(advance.id)];
        if (snapshot.game.catalog?.commodities.some(commodity => commodity.id === id)) return [label(id, 'commodity')];
        return [];
    }))];
    const key = `error.${failure.code}`;
    const message = t(key);
    const cause = failure.causeCode ? t(`error.${failure.causeCode}`) : '';
    const detail = cause && cause !== `error.${failure.causeCode}` && failure.causeCode !== failure.code ? cause : '';
    return <p className={failure.code === 'OUTCOME_CONFIRMED' ? 'success' : 'error'} role={failure.code === 'OUTCOME_CONFIRMED' ? 'status' : 'alert'}>{message === key ? t('error.SERVER') : message}{detail && ` · ${detail}`}{failure.entryIndex !== null && ` · ${t('history.editLine', {line: failure.entryIndex + 1})}`}{Number.isFinite(failure.parameters.required) && Number.isFinite(failure.parameters.available) && ` · ${t('error.quantities', failure.parameters)}`}{names.length > 0 && ` · ${names.join(', ')}`}</p>;
}
