import type {CardDefinition} from './api';
import {t} from './messages';

/** Show recycling behavior without requiring players to interpret a printed shape. */
export function CardRecurrence({definition}: {definition: Pick<CardDefinition, 'shape'>}) {
    return definition.shape === 'CIRCLE'
        ? <span className="recurring-badge" title={t('card.recurringHelp')}>↻ {t('card.recurring')}</span> : null;
}
