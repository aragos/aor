import {useEffect} from 'react';

const ready = new URL('./assets/favicon-ready.svg', import.meta.url).href;
const waiting = new URL('./assets/favicon-waiting.svg', import.meta.url).href;

/** A tab's color reflects an outstanding decision, including while the tab is in the background. */
export function useTurnFavicon(canAct: boolean) {
    useEffect(() => {
        let icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
        if (!icon) {
            icon = document.createElement('link');
            icon.rel = 'icon';
            icon.type = 'image/svg+xml';
            document.head.appendChild(icon);
        }
        icon.href = canAct ? ready : waiting;
        return () => { icon.href = waiting; };
    }, [canAct]);
}
