import {createContext, useContext} from 'react';
import {t} from './messages';

export const AccountContext = createContext<{handle: string; signOut: () => void} | null>(null);

/** Hosted identity and navigation share the page's overview bar. */
export function AccountControls({showGames = true}: {showGames?: boolean}) {
    const account = useContext(AccountContext);
    if (!account) return null;
    return <nav className="account-controls" aria-label={t('hosted.account')}>
        {showGames && <a href="/">{t('hosted.my-games')}</a>}
        <span>@{account.handle}</span>
        <button className="secondary" onClick={account.signOut}>{t('hosted.sign-out')}</button>
    </nav>;
}
