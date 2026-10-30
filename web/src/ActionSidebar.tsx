import {useLayoutEffect, useRef, useState} from 'react';
import type {CSSProperties, ReactNode} from 'react';
import type {Snapshot} from './api';
import {Activity} from './Activity';
import {t} from './messages';

const MIN_ACTIVITY = 48;
const DIVIDER = 10;

/** The split belongs to this live sidebar and survives polling and log minimization. */
export function ActionSidebar({children, snapshot, onInspectCard}: {
    children: ReactNode; snapshot: Snapshot; onInspectCard: (id: string) => void;
}) {
    const ref = useRef<HTMLDivElement>(null);
    const [open, setOpen] = useState(true);
    const [requestedHeight, setRequestedHeight] = useState<number | null>(null);
    const [bounds, setBounds] = useState({height: 0, maximum: MIN_ACTIVITY});
    const dragging = useRef<number | null>(null);
    useLayoutEffect(() => {
        const sidebar = ref.current;
        if (!sidebar) return;
        const measure = () => {
            const panel = sidebar.querySelector<HTMLElement>('.decision-panel');
            const header = sidebar.querySelector<HTMLElement>('.decision-header');
            const footer = sidebar.querySelector<HTMLElement>('.decision-actions');
            const style = panel && getComputedStyle(panel);
            const padding = style ? parseFloat(style.paddingTop) + parseFloat(style.paddingBottom) : 0;
            const decisionMinimum = (header?.offsetHeight ?? 0) + (footer?.offsetHeight ?? 0) + padding + 32;
            const height = sidebar.clientHeight;
            const maximum = Math.max(MIN_ACTIVITY, height - decisionMinimum - DIVIDER);
            setBounds(previous => previous.height === height && previous.maximum === maximum
                ? previous : {height, maximum});
        };
        const observer = new ResizeObserver(measure);
        observer.observe(sidebar);
        for (const element of sidebar.querySelectorAll('.decision-header, .decision-actions')) observer.observe(element);
        measure();
        return () => observer.disconnect();
    }, []);
    const activityHeight = Math.round(Math.min(bounds.maximum,
        Math.max(MIN_ACTIVITY, requestedHeight ?? bounds.height * .4)));
    const resize = (height: number) => setRequestedHeight(Math.min(bounds.maximum, Math.max(MIN_ACTIVITY, height)));
    return <div ref={ref} className="workspace-side" data-activity-open={open}
        style={{'--activity-height': `${activityHeight}px`, '--activity-divider': `${DIVIDER}px`,
            '--activity-minimum': `${MIN_ACTIVITY}px`} as CSSProperties}>
        {children}
        {open && <div className="activity-resizer" role="separator" tabIndex={0}
            aria-label={t('table.resizeActivity')} aria-orientation="horizontal"
            aria-valuemin={MIN_ACTIVITY} aria-valuemax={Math.round(bounds.maximum)} aria-valuenow={activityHeight}
            onDoubleClick={() => setRequestedHeight(null)}
            onPointerDown={event => {
                if (event.button !== 0) return;
                event.preventDefault();
                dragging.current = event.pointerId;
                event.currentTarget.setPointerCapture(event.pointerId);
            }}
            onPointerMove={event => {
                if (dragging.current !== event.pointerId || !ref.current) return;
                resize(ref.current.getBoundingClientRect().bottom - event.clientY - DIVIDER / 2);
            }}
            onPointerUp={() => {dragging.current = null;}}
            onPointerCancel={() => {dragging.current = null;}}
            onLostPointerCapture={() => {dragging.current = null;}}
            onKeyDown={event => {
                if (!['ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
                event.preventDefault();
                resize(event.key === 'Home' ? MIN_ACTIVITY : event.key === 'End' ? bounds.maximum
                    : activityHeight + (event.key === 'ArrowUp' ? 24 : -24));
            }}/>}
        <Activity snapshot={snapshot} onInspectCard={onInspectCard} open={open} setOpen={setOpen}/>
    </div>;
}
