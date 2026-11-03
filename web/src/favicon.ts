import {useEffect} from 'react';
import type {Snapshot} from './api';

const ready = new URL('./assets/favicon-ready.svg', import.meta.url).href;
const waiting = new URL('./assets/favicon-waiting.svg', import.meta.url).href;

/** Page metadata updates synchronously, including when background UI rendering is deferred. */
export function setTurnFavicon(canAct: boolean) {
    let icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
    if (!icon) {
        icon = document.createElement('link');
        icon.rel = 'icon';
        icon.type = 'image/svg+xml';
        document.head.appendChild(icon);
    }
    const href = canAct ? ready : waiting;
    if (icon.href !== href) icon.href = href;
}

export function hasPlayerAction(snapshot: Snapshot): boolean {
    return snapshot.viewerId !== null && snapshot.status !== 'CANCELLED' && snapshot.status !== 'ABANDONED' &&
        snapshot.game.stage !== 'FINISHED' && ((snapshot.prompt?.players.includes(snapshot.viewerId) ?? false) || (snapshot.abandonment?.outstanding.includes(snapshot.viewerId) ?? false));
}

export function useTurnFavicon(canAct: boolean) {
    useEffect(() => {
        setTurnFavicon(canAct);
        return () => setTurnFavicon(false);
    }, [canAct]);
}
