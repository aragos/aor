import {t} from './messages';

export function AutomatedOpponents({count, onChange, humanCount, version, disabled = false}: {
    count: number; onChange: (count: number) => void;
    humanCount: number; version?: string; disabled?: boolean;
}) {
    return <fieldset className="automated-opponents" disabled={disabled}>
        <legend>{t('bots.opponents')}</legend>
        <p className="muted small">{t('bots.experimental')}{version && <> {t('bots.updated', {version})}</>}</p>
        {Array.from({length: count}, (_, index) => <div className="automated-seat" key={index}>
            <span>{t('bots.number', {number: index + 1})}</span>
            <button type="button" className="secondary" aria-label={t('bots.removeNumber', {number: index + 1})}
                onClick={() => onChange(count - 1)}>{t('hosted.remove')}</button>
        </div>)}
        <button type="button" className="secondary" disabled={humanCount + count >= 6}
            onClick={() => onChange(count + 1)}>{t('bots.add')}</button>
        {count > 0 && <p className="muted small">{t('bots.hosting')}</p>}
        <p className="muted small">{t('bots.seatCount', {count: humanCount + count})}</p>
    </fieldset>;
}
