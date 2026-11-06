import type {HistoryEventView} from './api';

/** Recorded IDs identify a committed revision; unrelated revisions must not imply causality. */
function sameRevision(left: HistoryEventView, right: HistoryEventView): boolean {
    const revision = /^(\d+):/.exec(left.id)?.[1];
    return revision !== undefined && revision === /^(\d+):/.exec(right.id)?.[1];
}

function continuesRow(row: HistoryEventView[], event: HistoryEventView): boolean {
    const first = row[0];
    const previous = row[row.length - 1];
    if (event.turnNumber !== first.turnNumber || event.stage !== first.stage) return false;

    // Draws can occur between the two rolls. Retain each reward beside the roll that caused it.
    if (first.marketRoll) {
        if (event.kind === 'DRAW_CARD' && event.drawReason === 'SHORTAGE') return true;
        return event.kind === 'ROLL' && !!event.marketRoll &&
            row.filter(member => member.marketRoll).length < 2;
    }

    // Cathedral can resolve the preceding attack in a later revision after a private response.
    if (event.kind === 'COMPETITION_RESULT' && previous === first) {
        return ['ATTACK', 'PLACE'].includes(first.kind) && event.actor === first.actor &&
            first.provinces.length === 1 && first.provinces[0] === event.provinces[0];
    }
    if (!sameRevision(previous, event)) return false;
    if (first.kind === 'BID_CAPITAL') return event.kind === 'BID_CAPITAL';
    if (first.kind === 'DRAW_CARD') {
        return event.kind === 'DRAW_CARD' && event.drawReason === first.drawReason;
    }
    if (event.kind === 'ORDER_CHANGED') {
        return event.id.startsWith(`${previous.id}:`) &&
            ['PLAY_CARD', 'CIVIL_WAR_LOSS', 'REPLACE_EVENT_TOKEN'].includes(first.kind);
    }
    if (event.kind === 'TRADE_CARD') return first.kind === 'OFFER_CARD' && event.target === first.actor;
    if (event.actor !== first.actor) return false;
    if (event.kind === 'WAR_RESULT') return first.kind === 'PLAY_CARD' && first.card === 'war';
    if (event.kind !== 'DRAW_CARD') return false;
    return (first.kind === 'BUY_CARD' && event.drawReason === 'URBAN_ASCENDANCY') ||
        (first.kind === 'BUY_EXPANSION_CARD' && event.drawReason === 'EXPANSION_PURCHASE');
}

/** Presentation groups preserve every recorded event, its order, audience and entry-time facts. */
export function activityRows(events: HistoryEventView[]): HistoryEventView[][] {
    const rows: HistoryEventView[][] = [];
    for (const event of events) {
        const row = rows[rows.length - 1];
        if (row && continuesRow(row, event)) row.push(event);
        else rows.push([event]);
    }
    return rows;
}
