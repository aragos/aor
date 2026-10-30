import type {ApiError, Snapshot} from './api';
import {CapitalLabel} from './IdentityLabel';
import {advanceName, label, playerName, provinceName} from './labels';
import {t} from './messages';

/** Only recognized viewer-visible entities become reference labels; raw command fragments stay out. */
export function DiagnosticMessage({failure, snapshot}: {failure: ApiError; snapshot: Snapshot}) {
    const entities = [...new Set(failure.entityIds)].flatMap(id => {
        const player = snapshot.game.players.find(player => player.id === id);
        if (player) return [{id, name: playerName(snapshot, id), capital: player.capital}];
        const province = snapshot.game.catalog?.provinces.find(province => province.id === id);
        if (province) return [{id, name: provinceName(id), capital: province.capital}];
        const advance = snapshot.game.catalog?.advances.find(advance => advance.id === id || advance.letter === id);
        if (advance) return [{id, name: advanceName(advance.id), capital: null}];
        if (snapshot.game.catalog?.commodities.some(commodity => commodity.id === id)) {
            return [{id, name: label(id, 'commodity'), capital: null}];
        }
        return [];
    });
    const key = `error.${failure.code}`;
    const message = t(key);
    const cause = failure.causeCode ? t(`error.${failure.causeCode}`) : '';
    const detail = cause && cause !== `error.${failure.causeCode}` && failure.causeCode !== failure.code ? cause : '';
    return <p className={failure.code === 'OUTCOME_CONFIRMED' ? 'success' : 'error'} role={failure.code === 'OUTCOME_CONFIRMED' ? 'status' : 'alert'}>{message === key ? t('error.SERVER') : message}{detail && ` · ${detail}`}{failure.entryIndex !== null && ` · ${t('history.editLine', {line: failure.entryIndex + 1})}`}{Number.isFinite(failure.parameters.required) && Number.isFinite(failure.parameters.available) && ` · ${t('error.quantities', failure.parameters)}`}{entities.length > 0 && <> · {entities.map((entity, index) => <span key={entity.id}>{index > 0 && ', '}<CapitalLabel capital={entity.capital}>{entity.name}</CapitalLabel></span>)}</>}</p>;
}
