import type {HistoryEventView} from './api';

/** Combine only the two neighboring market rolls; recorded events and their facts stay intact. */
export function activityRows(events: HistoryEventView[]): HistoryEventView[][] {
    const rows: HistoryEventView[][] = [];
    for (let index = 0; index < events.length; index++) {
        const event = events[index];
        const next = events[index + 1];
        if (event.kind === 'ROLL' && event.marketRoll && next?.kind === 'ROLL' && next.marketRoll &&
            event.turnNumber === next.turnNumber && event.stage === next.stage) {
            rows.push([event, next]);
            index++;
        } else {
            rows.push([event]);
        }
    }
    return rows;
}
