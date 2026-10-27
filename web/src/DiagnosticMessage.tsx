import type {ApiError, Snapshot} from './api';
import {advanceName, playerName, provinceName} from './labels';
import {t} from './messages';

/** Only recognized viewer-visible entities become reference labels; raw command fragments stay out. */
export function DiagnosticMessage({failure, snapshot}: {failure: ApiError; snapshot: Snapshot}) {
    const names = [...new Set(failure.entityIds.flatMap(id => {
        if (snapshot.game.players.some(player => player.id === id)) return [playerName(snapshot, id)];
        if (snapshot.game.catalog?.provinces.some(province => province.id === id)) return [provinceName(id)];
        const advance = snapshot.game.catalog?.advances.find(advance => advance.id === id || advance.letter === id);
        if (advance) return [advanceName(advance.id)];
        return [];
    }))];
    const key = `error.${failure.code}`;
    const message = t(key);
    return <p className="error" role="alert">{message === key ? t('error.SERVER') : message}{names.length > 0 && ` · ${names.join(', ')}`}</p>;
}
