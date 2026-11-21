import {t} from './messages';

const setup = ['CONFIGURATION', 'SETUP_RETURN', 'BIDDING', 'CAPITAL_CHOICE'];
const turn = ['TOKEN_PURCHASE', 'DRAW_CARD', 'BUY_CARD', 'PLAY_CARDS', 'PURCHASE', 'EXPANSION', 'INCOME'];
const closing = ['FINAL_CARD_PLAY', 'FINISHED'];
const parentPhase: Record<string, string> = {
    SHUFFLE: 'CONFIGURATION', INITIAL_DRAW: 'SETUP_RETURN', CAPITAL_TIE: 'BIDDING',
    TURN_ORDER_TIE: 'TOKEN_PURCHASE',
};

export function PhaseTimeline({stage, spectator}: {stage: string; spectator: boolean}) {
    const current = parentPhase[stage] ?? stage;
    const phases = setup.includes(current) ? setup : closing.includes(current) ? closing : turn;
    const index = phases.indexOf(current);
    return <div className="header-stage">
        <ol className="phase-timeline" aria-label={t('table.phaseTimeline')}>
            {(index < 0 ? [stage] : phases).map((phase, position) => <li key={phase}
                data-distance={Math.abs(position - Math.max(index, 0))}
                aria-current={position === Math.max(index, 0) ? 'step' : undefined}>
                {position === Math.max(index, 0) ? <strong>{t(`stage.${stage}`)}</strong> : t(`stage.${phase}`)}
            </li>)}
        </ol>
        {spectator && <span className="spectator-status">{t('table.spectating')}</span>}
    </div>;
}
