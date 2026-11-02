import {createContext, lazy, Suspense, useContext, useState} from 'react';
import type {ReactNode} from 'react';
import {createPortal} from 'react-dom';
import {t} from './messages';
import type {PlayerView, PublicCatalog} from './api';

export const ReferenceCatalogContext = createContext<PublicCatalog | null>(null);

/** Only public identities and resolved assignments enter the live printed-track lookup. */
export const ReferenceOrderContext = createContext<{
    viewerId: string;
    players: Pick<PlayerView, 'id' | 'name' | 'capital'>[];
    turnOrder: string[];
    orderBoxes: Record<string, number>;
} | null>(null);

const ReferencePopup = lazy(() => import('./ReferencePopup'));

/** Inspect reference material in place while retaining the prepared game action. */
export function HelpLink({topic = '', label = 'Help', compact = true, icon}: {
    topic?: string; label?: string; compact?: boolean; icon?: ReactNode;
}) {
    const [open, setOpen] = useState(false);
    const catalog = useContext(ReferenceCatalogContext);
    return <><button type="button" className={compact ? 'help-link' : 'reference-link'}
        data-help-topic={topic} aria-label={`Help: ${label}`} title={label} aria-haspopup="dialog"
        onClick={event => {event.preventDefault(); event.stopPropagation(); setOpen(true);}}>
        {compact ? <>{icon}?</> : label}
    </button>{open && createPortal(<Suspense fallback={<p role="status">{t('reference.loading')}</p>}>
        <ReferencePopup initialTopic={topic} close={() => setOpen(false)} catalog={catalog}/>
    </Suspense>, document.body)}</>;
}
