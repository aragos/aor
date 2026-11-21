import type {Snapshot} from '../api';
import {t} from '../messages';
import {LabelMessage, PlayerLabel} from '../IdentityLabel';
import type {useBotAutomation} from './useBotAutomation';

export type BotControlState = ReturnType<typeof useBotAutomation>;

export function BotControls({snapshot, status, operating, control, savingPhasePause, phasePauseError, setPhasePause}:
    {snapshot: Snapshot} & BotControlState) {
    const automation = snapshot.automation;
    if (!automation?.players.length) return null;
    const resume = ['paused', 'stopped', 'uncertain', 'other'].includes(status);
    const publicStatus = !automation.canControl && !automation.paused && status !== 'finished' ? 'hosted' : status;
    return <div className="bot-controls">
        <p><strong>{t('bots.opponents')}</strong></p>
        <ul>{automation.players.map(player => <li key={player.id}>{player.name} · {t(`bots.difficulty.${player.difficulty}`)}</li>)}</ul>
        <p className="small" role="status">{t(`bots.status.${publicStatus}`)}</p>
        {automation.canControl && <>
            <label className="bot-phase-option">
                <input type="checkbox" checked={automation.pauseAfterPhase}
                    disabled={operating || savingPhasePause}
                    onChange={event => {void setPhasePause(event.target.checked);}}/>
                {t('bots.pauseAfterPhase')}
            </label>
            {phasePauseError && <p className="error" role="alert">{t('bots.phasePauseError')}</p>}
            {!automation.paused && <div className="button-row">
                <button type="button" className="secondary" disabled={operating || savingPhasePause}
                    onClick={() => {void control(resume ? 'resume' : 'pause');}}>
                    {t(resume ? status === 'other' ? 'bots.runHere' : 'bots.resume' : 'bots.pause')}
                </button>
            </div>}
        </>}
    </div>;
}

export function BotPause({snapshot, controls}: {
    snapshot: Snapshot; controls?: BotControlState;
}) {
    const automation = snapshot.automation;
    if (!automation?.paused || snapshot.status !== 'ACTIVE' || snapshot.game.stage === 'FINISHED') return null;
    const phase = automation.pausedAfterPhase;
    const player = automation.pausedAfterPlayer;
    return <div className="bot-phase-pause">
        <span role="status">{!phase ? t('bots.status.paused') : player
            ? <LabelMessage text={t('bots.pausedAfterPlayer', {phase: t(`stage.${phase}`)})}
                values={{player: <PlayerLabel snapshot={snapshot} id={player}/>}}/>
            : t('bots.pausedAfterPhase', {phase: t(`stage.${phase}`)})}</span>
        {automation.canControl && controls && <button type="button" disabled={controls.operating || controls.savingPhasePause}
            onClick={() => {void controls.control('resume');}}>{t('bots.resume')}</button>}
    </div>;
}
