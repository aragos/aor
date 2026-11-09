import {useState} from 'react';
import type {ReactNode} from 'react';
import {t} from './messages';

type Tool = 'personal' | 'configuration' | 'commands';

/** A stable tab row and separate close action keep navigation targets fixed as content expands. */
export function TableTools({personal, configuration, commands, revision}: {
    personal: ReactNode; configuration: ReactNode; commands: ReactNode; revision: number;
}) {
    const [active, setActive] = useState<Tool | null>(null);
    const tabs: Tool[] = ['personal', 'configuration', ...(commands ? ['commands'] as const : [])];
    const content = {personal, configuration, commands};
    return <div className="table-tools">
        <div className="tool-bar">
            <nav role="tablist" aria-label={t('table.tools')}>
                {tabs.map((tab, index) => <button key={tab} type="button" role="tab"
                    id={`tool-tab-${tab}`} aria-controls={`tool-panel-${tab}`}
                    aria-selected={active === tab} tabIndex={active === tab || active === null && index === 0 ? 0 : -1}
                    onClick={() => setActive(tab)} onKeyDown={event => {
                        const offset = event.key === 'ArrowRight' ? 1 : event.key === 'ArrowLeft' ? -1 : 0;
                        const next = event.key === 'Home' ? tabs[0] : event.key === 'End' ? tabs[tabs.length - 1]
                            : offset ? tabs[(index + offset + tabs.length) % tabs.length] : null;
                        if (!next) return;
                        event.preventDefault();
                        setActive(next);
                        document.getElementById(`tool-tab-${next}`)?.focus();
                    }}>{t(tab === 'personal' ? 'options.personal' : `table.${tab}`)}
                    {tab === 'commands' && <small>{t('game.revision')} {revision}</small>}
                </button>)}
            </nav>
            <button className="tool-close secondary" type="button" disabled={active === null}
                aria-label={t('table.closeTools')} onClick={() => {
                    document.getElementById(`tool-tab-${active}`)?.focus();
                    setActive(null);
                }}>×</button>
        </div>
        <div className="tool-panels" hidden={active === null}>
            {tabs.map(tab => <div key={tab} id={`tool-panel-${tab}`} role="tabpanel"
                aria-labelledby={`tool-tab-${tab}`} hidden={active !== tab}>{content[tab]}</div>)}
        </div>
    </div>;
}
