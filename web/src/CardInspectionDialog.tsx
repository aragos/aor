import {useLayoutEffect, useRef} from 'react';
import type {ReactNode} from 'react';

/** A reference popup leaves the prepared action mounted and returns focus without moving the table. */
export function CardInspectionDialog({label, close, children}: {
    label: string; close: () => void; children: ReactNode;
}) {
    const ref = useRef<HTMLDialogElement>(null);
    useLayoutEffect(() => {
        const dialog = ref.current;
        const opener = document.activeElement;
        dialog?.showModal();
        return () => {
            dialog?.close();
            if (opener instanceof HTMLElement && opener.isConnected) {
                opener.focus({preventScroll: true});
            }
        };
    }, []);
    return <dialog ref={ref} className="card-inspection-dialog" aria-label={label}
        onCancel={event => {event.preventDefault(); close();}}
        onClick={event => {
            if (event.target !== event.currentTarget) return;
            const bounds = event.currentTarget.getBoundingClientRect();
            if (event.clientX < bounds.left || event.clientX > bounds.right ||
                event.clientY < bounds.top || event.clientY > bounds.bottom) close();
        }}>{children}</dialog>;
}
