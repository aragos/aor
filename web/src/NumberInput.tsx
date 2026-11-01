import {useEffect, useRef} from 'react';
import type {InputHTMLAttributes} from 'react';

/** Clear an initial zero on focus while retaining native validation and wheel protection. */
export function NumberInput({onValueChange, onFocus, ...props}: Omit<
    InputHTMLAttributes<HTMLInputElement>, 'type' | 'value' | 'defaultValue' | 'onChange'
> & {value: string; onValueChange: (value: string) => void}) {
    const ref = useRef<HTMLInputElement>(null);
    useEffect(() => {
        const input = ref.current;
        if (!input) return;
        const preventWheelChange = (event: WheelEvent) => {
            if (document.activeElement === input) event.preventDefault();
        };
        input.addEventListener('wheel', preventWheelChange, {passive: false});
        return () => input.removeEventListener('wheel', preventWheelChange);
    }, []);
    return <input {...props} ref={ref} type="number"
        onChange={event => onValueChange(event.target.value)}
        onFocus={event => {
            onFocus?.(event);
            if (event.currentTarget.value === '0') onValueChange('');
        }}/>;
}
