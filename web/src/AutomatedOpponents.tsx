import type {BotDifficulty} from './api';
import {t} from './messages';

export function AutomatedOpponents({value, onChange, humanCount, disabled = false}: {
    value: BotDifficulty[]; onChange: (value: BotDifficulty[]) => void;
    humanCount: number; disabled?: boolean;
}) {
    return <fieldset className="automated-opponents" disabled={disabled}>
        <legend>{t('bots.opponents')}</legend>
        {value.map((difficulty, index) => <div className="automated-seat" key={index}>
            <label htmlFor={`bot-difficulty-${index}`}>{t('bots.number', {number: index + 1})}</label>
            <select id={`bot-difficulty-${index}`} value={difficulty} onChange={event => {
                onChange(value.map((current, position) => position === index ? event.target.value as BotDifficulty : current));
            }}>{(['EASY', 'NORMAL', 'HARD'] as const).map(level => <option key={level} value={level}>{t(`bots.difficulty.${level}`)}</option>)}</select>
            <button type="button" className="secondary" aria-label={t('bots.removeNumber', {number: index + 1})}
                onClick={() => onChange(value.filter((_, position) => position !== index))}>{t('hosted.remove')}</button>
        </div>)}
        <button type="button" className="secondary" disabled={humanCount + value.length >= 6}
            onClick={() => onChange([...value, 'NORMAL'])}>{t('bots.add')}</button>
        {value.length > 0 && <p className="muted small">{t('bots.hosting')}</p>}
        <p className="muted small">{t('bots.seatCount', {count: humanCount + value.length})}</p>
    </fieldset>;
}
