import {useEffect, useRef} from 'react';
import type {Snapshot} from './api';
import {decisionIdentity} from './decision';
import {t} from './messages';
import {isBoolean, useStoredSetting} from './preferences';

/** Browser preferences grant standing permission only for live, owned, legal decisions. */
export function usePersonalOptions(snapshot: Snapshot, busy: boolean,
    submit: (command: string) => Promise<boolean>) {
    const [darkMode, setDarkMode] = useStoredSetting('dark-mode', false, isBoolean);
    const [skipWindmill, setSkipWindmill] = useStoredSetting('skip-windmill', false, isBoolean);
    const [buyUrbanCard, setBuyUrbanCard] = useStoredSetting('buy-urban-card', false, isBoolean);
    const attempted = useRef<string | null>(null);
    const send = useRef(submit);
    send.current = submit;
    const decision = decisionIdentity(snapshot);
    const ownOpportunity = snapshot.status === 'ACTIVE' && snapshot.viewerId !== null &&
        snapshot.prompt?.messageId === 'prompt.windmill' &&
        snapshot.prompt.players.includes(snapshot.viewerId) &&
        snapshot.legalChoices.commands.includes('windmill') &&
        snapshot.legalChoices.commands.includes('pass');
    const urbanOpportunity = snapshot.status === 'ACTIVE' && snapshot.viewerId !== null &&
        snapshot.prompt?.messageId === 'prompt.buy-card' &&
        snapshot.prompt.players.includes(snapshot.viewerId) &&
        snapshot.game.players.some(player => player.id === snapshot.viewerId &&
            player.advances.includes('URBAN_ASCENDANCY')) &&
        snapshot.legalChoices.commands.includes('buy-card') &&
        snapshot.game.cardPurchaseOffer?.blockers.length === 0;
    const command = skipWindmill && ownOpportunity ? 'pass'
        : buyUrbanCard && urbanOpportunity ? 'buy-card' : null;
    useEffect(() => {
        if (!command || busy || snapshot.planning?.saved?.armed || attempted.current === decision) return;
        // Mark before sending: StrictMode effect replay and polling must not submit twice.
        attempted.current = decision;
        void send.current(command);
    }, [command, busy, decision, snapshot.planning?.saved?.armed]);
    return {darkMode, setDarkMode, skipWindmill, setSkipWindmill, buyUrbanCard, setBuyUrbanCard};
}

export function PersonalOptions({options}: {options: ReturnType<typeof usePersonalOptions>}) {
    return <div className="personal-options">
        <label className="check-label"><input type="checkbox" checked={options.skipWindmill}
            onChange={event => options.setSkipWindmill(event.target.checked)}/>{t('options.skipWindmill')}</label>
        <label className="check-label"><input type="checkbox" checked={options.darkMode}
            onChange={event => options.setDarkMode(event.target.checked)}/>{t('options.darkMode')}</label>
        <label className="check-label"><input type="checkbox" checked={options.buyUrbanCard}
            onChange={event => options.setBuyUrbanCard(event.target.checked)}/>{t('options.buyUrbanCard')}</label>
        <p className="small muted">{t('options.browser')}</p>
    </div>;
}
