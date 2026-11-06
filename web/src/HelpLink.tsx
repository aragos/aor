import {createContext, lazy, Suspense, useContext, useState} from 'react';
import type {ReactNode} from 'react';
import {createPortal} from 'react-dom';
import {t} from './messages';
import type {PlayerView, PublicCatalog, Snapshot} from './api';
import {CardInspectionDialog} from './CardInspectionDialog';

export const ReferenceCatalogContext = createContext<PublicCatalog | null>(null);

/** Public live positions and card counts update open lookups without replacing action drafts. */
export const ReferenceGameContext = createContext<{
    viewerId: string | null;
    players: Pick<PlayerView, 'id' | 'name' | 'capital' | 'boardMarkers' | 'misery' | 'ships'>[];
    turnOrder: string[];
    orderBoxes: Record<string, number>;
    commodityCards: Snapshot['game']['commodityCards'];
    commodityPayouts: Snapshot['game']['commodityPayouts'];
    unplayedCards: Snapshot['game']['unplayedCards'];
    cardInspections: Snapshot['game']['cardInspections'];
} | null>(null);

const ReferencePopup = lazy(() => import('./ReferencePopup'));

/** Keep help visually on its action without nesting interactive buttons. */
export function ButtonHelp({children, topic, label}: {
    children: ReactNode; topic: string; label: string;
}) {
    return <span className="button-with-help">{children}<HelpLink topic={topic} label={label}/></span>;
}

/** Inspect reference material in place while retaining the prepared game action. */
export function HelpLink({topic = '', label = 'Help', compact = true, commodityChoices, children}: {
    topic?: string; label?: string; compact?: boolean; children?: ReactNode;
    /** Canonical commodity IDs from one card, in printed order. */
    commodityChoices?: readonly string[];
}) {
    const [open, setOpen] = useState(false);
    const catalog = useContext(ReferenceCatalogContext);
    return <><button type="button" className={compact ? 'help-link' : 'reference-link'}
        data-help-topic={topic} aria-label={`Help: ${label}`} title={label} aria-haspopup="dialog"
        onClick={event => {event.preventDefault(); event.stopPropagation(); setOpen(true);}}>
        {children ?? (compact ? '?' : label)}
    </button>{open && createPortal(<CardInspectionDialog label={t('reference.help-and-reference')}
        close={() => setOpen(false)} className="reference-dialog">
        <Suspense fallback={<>
            <button type="button" className="close-detail" aria-label={t('table.close')} onClick={() => setOpen(false)}>×</button>
            <p role="status">{t('reference.loading')}</p>
        </>}>
            <ReferencePopup initialTopic={topic} close={() => setOpen(false)} catalog={catalog}
                commodityChoices={commodityChoices}/>
        </Suspense>
    </CardInspectionDialog>, document.body)}</>;
}
