import type {CardDefinition} from './api';
import {t} from './messages';

/** Event descriptions shared by the game inspector and public reference. */
export function CardEventText({definition}: {definition: CardDefinition}) {
    return definition.kind === 'event'
        ? <span className="card-event-text">{t(`${definition.messageId}.effect`)}</span> : null;
}
