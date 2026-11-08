import {useEffect, useRef} from 'react';
import type {Snapshot} from './api';
import {decisionIdentity} from './decision';
import {t} from './messages';
import {isBoolean, useStoredSetting} from './preferences';

/** Declining an optional trade is explicit standing permission, scoped to live owned decisions. */
export function usePersonalOptions(snapshot: Snapshot, busy: boolean,
    submit: (command: string) => Promise<boolean>) {
    const [darkMode, setDarkMode] = useStoredSetting('dark-mode', false, isBoolean);
    const [skipWindmill, setSkipWindmill] = useStoredSetting('skip-windmill', false, isBoolean);
    const attempted = useRef<string | null>(null);
    const send = useRef(submit);
    send.current = submit;
    const decision = decisionIdentity(snapshot);
    const ownOpportunity = snapshot.status === 'ACTIVE' && snapshot.viewerId !== null &&
        snapshot.prompt?.messageId === 'prompt.windmill' &&
        snapshot.prompt.players.includes(snapshot.viewerId) &&
        snapshot.legalChoices.commands.includes('windmill') &&
        snapshot.legalChoices.commands.includes('pass');
    useEffect(() => {
        if (!skipWindmill || !ownOpportunity || busy || attempted.current === decision) return;
        // Mark before sending: StrictMode effect replay and polling must not submit twice.
        attempted.current = decision;
        void send.current('pass');
    }, [skipWindmill, ownOpportunity, busy, decision]);
    return {darkMode, setDarkMode, skipWindmill, setSkipWindmill};
}

export function PersonalOptions({options}: {options: ReturnType<typeof usePersonalOptions>}) {
    return <details className="personal-options">
        <summary>{t('options.personal')}</summary>
        <label className="check-label"><input type="checkbox" checked={options.skipWindmill}
            onChange={event => options.setSkipWindmill(event.target.checked)}/>{t('options.skipWindmill')}</label>
        <label className="check-label"><input type="checkbox" checked={options.darkMode}
            onChange={event => options.setDarkMode(event.target.checked)}/>{t('options.darkMode')}</label>
        <p className="small muted">{t('options.browser')}</p>
    </details>;
}
