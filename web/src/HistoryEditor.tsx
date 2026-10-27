import {useState} from 'react';
import type {Snapshot} from './api';
import {t} from './messages';

/** Retention references preserve hidden answers; new lines remain ordinary player commands. */
export function HistoryEditor({snapshot, busy, edit}: {
    snapshot: Snapshot; busy: boolean; edit: (fromEntry: number, lines: string[]) => Promise<boolean>;
}) {
    const [from, setFrom] = useState('');
    const [text, setText] = useState('');
    const [base, setBase] = useState(snapshot.revision);
    const lower = snapshot.undo.fromEntry;
    if (lower === null) return null;
    const position = Number(from);
    const valid = from !== '' && Number.isInteger(position) && position >= lower && position < snapshot.undo.entryCount;
    const stale = from !== '' && base !== snapshot.revision;
    return <details className="history-editor"><summary>{t('history.edit')}</summary>
        <p>{t('history.editHelp')}</p>
        <label htmlFor="history-from">{t('history.fromEntry')}</label>
        <input id="history-from" type="number" min={lower} max={snapshot.undo.entryCount - 1} value={from}
            onChange={event => setFrom(event.target.value)}/>
        <button type="button" className="secondary" disabled={busy || !valid} onClick={() => {
            setText(snapshot.undo.actions.filter(action => action.entry >= position).map(action => `@${action.entry}`).join('\n'));
            setBase(snapshot.revision);
        }}>{t('history.loadSuffix')}</button>
        <ol className="editable-actions">{snapshot.undo.actions.filter(action => !valid || action.entry >= position).map(action =>
            <li key={action.entry}><code>@{action.entry} · {action.text}</code></li>)}</ol>
        <form onSubmit={async event => {
            event.preventDefault();
            if (!busy && valid && !stale && await edit(position, text.split('\n').map(line => line.trim()).filter(Boolean))) {
                setFrom(''); setText('');
            }
        }}>
            <label htmlFor="history-suffix">{t('history.replacement')}</label>
            <textarea id="history-suffix" rows={8} value={text} onChange={event => setText(event.target.value)}/>
            {stale && <p className="blocker">{t('history.staleDraft')}</p>}
            <button disabled={busy || !valid || stale}>{t('history.publish')}</button>
        </form>
    </details>;
}
