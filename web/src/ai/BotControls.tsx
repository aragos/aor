import type {Snapshot} from '../api';
import {t} from '../messages';
import type {BotRunnerStatus} from './botRunner';

export function BotControls({snapshot, status, operating, control}: {
    snapshot: Snapshot; status: BotRunnerStatus; operating: boolean;
    control: (operation: 'pause' | 'resume') => Promise<void>;
}) {
    const automation = snapshot.automation;
    if (!automation?.players.length) return null;
    const resume = ['paused', 'stopped', 'uncertain', 'other'].includes(status);
    const publicStatus = !automation.canControl && !automation.paused && status !== 'finished' ? 'hosted' : status;
    return <div className="bot-controls">
        <p><strong>{t('bots.opponents')}</strong></p>
        <ul>{automation.players.map(player => <li key={player.id}>{player.name} · {t(`bots.difficulty.${player.difficulty}`)}</li>)}</ul>
        <p className="small" role="status">{t(`bots.status.${publicStatus}`)}</p>
        {automation.canControl && status !== 'finished' && <div className="button-row">
            <button type="button" className="secondary" disabled={operating} onClick={() => {void control(resume ? 'resume' : 'pause');}}>
                {t(resume ? status === 'other' ? 'bots.runHere' : 'bots.resume' : 'bots.pause')}
            </button>
        </div>}
    </div>;
}
