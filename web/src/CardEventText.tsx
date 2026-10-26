import type {CardDefinition} from './api';
import {t} from './messages';

/** Reference text is visible in both the hand and the current decision. */
export function CardEventText({definition}: {definition: CardDefinition}) {
    return definition.kind === 'event'
        ? <span className="card-event-text">{t(`${definition.messageId}.effect`)}</span> : null;
}
