import {useState} from 'react';
import type {Snapshot} from './api';
import {t} from './messages';
import {HistoryEditor} from './HistoryEditor';
import {ChoiceButtons} from './DecisionControls';
import {playerName} from './labels';
import {displayHistoryLine} from './history';
import {useStoredSetting} from './preferences';

type HistoryIdentifiers = 'capitals' | 'names' | 'ids';
const isHistoryIdentifiers = (value: unknown): value is HistoryIdentifiers =>
    value === 'capitals' || value === 'names' || value === 'ids';

export function CommandDock({snapshot, busy, submit, open, onToggle, editHistory}: {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>;
    open: boolean; onToggle: (open: boolean) => void;
    editHistory?: (fromEntry: number, lines: string[]) => Promise<boolean>;
}) {
    const [command, setCommand] = useState('');
    const [identifiers, setIdentifiers] = useStoredSetting<HistoryIdentifiers>('history-identifiers', 'capitals', isHistoryIdentifiers);
    const aliases = Object.fromEntries(snapshot.game.players.map(player => [player.id,
        identifiers === 'capitals' ? playerName(snapshot, player.id) : identifiers === 'names' ? player.name : player.id]));
    const format = (line: string) => displayHistoryLine(line, aliases);
    return <details className="command-dock" open={open} onToggle={event => onToggle(event.currentTarget.open)}>
        <summary>{t('table.commands')} <small>{t('game.revision')} {snapshot.revision}</small></summary>
        {snapshot.game.stage !== 'FINISHED' && <form className="console" onSubmit={async event => {
            event.preventDefault();
            if (await submit(command)) setCommand(current => current === command ? '' : current);
        }}><label htmlFor="command">{t('controls.command')}</label>
            <div className="inline-form"><input id="command" className="command-input" value={command} onChange={event => setCommand(event.target.value)}
                placeholder={t('controls.commandPlaceholder')} autoComplete="off" required/>
                <button disabled={busy || !command.trim()}>{t('controls.send')}</button></div>
            <div className="choices"><code>{snapshot.legalChoices.commands.join(' · ')}</code></div>
        </form>}
        <div className="command-private-hand">{snapshot.game.hand.map(card => <span key={card.reference}><code>{card.reference}</code> {t(card.definition.messageId)}</span>)}</div>
        {snapshot.undo && <p>{t('history.policy')}: {t(`undoPolicy.${snapshot.undo.policy.toLowerCase().replace(/_/g, '-')}`)}</p>}
        {editHistory && snapshot.undo && <HistoryEditor snapshot={snapshot} busy={busy} edit={editHistory} format={format}/>}
        <h3>{t('game.history')}</h3>
        <p>{t('history.identifiers')}</p><ChoiceButtons id="history-identifiers" value={identifiers} onChange={setIdentifiers}
            choices={(['capitals', 'names', 'ids'] as const).map(value => ({value, label: t(`history.identifiers.${value}`)}))}/>
        <pre>{snapshot.history.map(format).join('\n')}</pre>
    </details>;
}
