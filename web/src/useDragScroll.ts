import {useLayoutEffect, useRef, useState} from 'react';
import type {MouseEvent, PointerEvent} from 'react';

/** Mouse/pen dragging supplements the scrollbar; touch keeps the browser's native scrolling. */
export function useDragScroll(contentKey: string) {
    const ref = useRef<HTMLDivElement>(null);
    const [overflow, setOverflow] = useState(false);
    const [dragging, setDragging] = useState(false);
    const gesture = useRef<{pointer: number; x: number; y: number; scroll: number; active: boolean} | null>(null);
    const suppressClick = useRef(false);
    useLayoutEffect(() => {
        const element = ref.current;
        if (!element) return;
        const measure = () => setOverflow(element.scrollWidth > element.clientWidth + 1);
        const observer = new ResizeObserver(measure);
        observer.observe(element);
        [...element.children].forEach(child => observer.observe(child));
        measure();
        return () => observer.disconnect();
    }, [contentKey]);
    const finish = () => {
        gesture.current = null;
        setDragging(false);
    };
    return {
        ref,
        'data-draggable': overflow,
        'data-dragging': dragging,
        onPointerDown(event: PointerEvent<HTMLDivElement>) {
            suppressClick.current = false;
            if (!overflow || event.button !== 0 || event.pointerType === 'touch') return;
            gesture.current = {pointer: event.pointerId, x: event.clientX, y: event.clientY, scroll: event.currentTarget.scrollLeft, active: false};
        },
        onPointerMove(event: PointerEvent<HTMLDivElement>) {
            const current = gesture.current;
            if (!current || current.pointer !== event.pointerId) return;
            if (!(event.buttons & 1)) { finish(); return; }
            const distance = event.clientX - current.x;
            if (!current.active) {
                if (Math.abs(distance) < 6 || Math.abs(distance) <= Math.abs(event.clientY - current.y)) return;
                current.active = true;
                suppressClick.current = true;
                event.currentTarget.setPointerCapture(event.pointerId);
                setDragging(true);
            }
            event.preventDefault();
            event.currentTarget.scrollLeft = current.scroll - distance;
        },
        onPointerUp: finish,
        onPointerCancel: finish,
        onLostPointerCapture: finish,
        onClickCapture(event: MouseEvent<HTMLDivElement>) {
            if (!suppressClick.current || event.detail === 0) return;
            suppressClick.current = false;
            event.preventDefault();
            event.stopPropagation();
        },
        onDragStart(event: MouseEvent<HTMLDivElement>) { if (overflow) event.preventDefault(); },
    };
}
