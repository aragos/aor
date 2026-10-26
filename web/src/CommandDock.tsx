import {useState} from 'react';
import type {Snapshot} from './api';
import {t} from './messages';

export function CommandDock({snapshot, busy, submit, open, onToggle}: {
    snapshot: Snapshot; busy: boolean; submit: (command: string) => Promise<boolean>;
    open: boolean; onToggle: (open: boolean) => void;
}) {
    const [command, setCommand] = useState('');
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
        <h3>{t('game.history')}</h3><pre>{snapshot.history.join('\n')}</pre>
    </details>;
}
