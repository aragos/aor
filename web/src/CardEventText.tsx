import type {CardDefinition} from './api';
import {t} from './messages';

/** Static card rules for the public reference page. */
export function CardEventText({definition}: {definition: CardDefinition}) {
    return definition.kind === 'event'
        ? <span className="card-event-text">{t(`${definition.messageId}.effect`)}</span> : null;
}
