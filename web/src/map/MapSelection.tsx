import {createContext, useContext, useEffect, useId, useRef, useState} from 'react';
import type {Dispatch, ReactNode, SetStateAction} from 'react';

interface Picker {
    id: string;
    title: string;
    eligible: string[];
    selected: string[];
    pick: (province: string) => void;
}

const MapSelection = createContext<{
    picker: Picker | null;
    setPicker: Dispatch<SetStateAction<Picker | null>>;
}>({picker: null, setPicker: () => undefined});

export function MapSelectionProvider({children}: {children: ReactNode}) {
    const [picker, setPicker] = useState<Picker | null>(null);
    return <MapSelection.Provider value={{picker, setPicker}}>{children}</MapSelection.Provider>;
}

export const useMapSelection = () => useContext(MapSelection);

/** Map clicks only edit the requesting form. Its submit button sends the order. */
export function MapPicker({title, eligible, selected, onPick, disabled = false}: {
    title: string;
    eligible: string[];
    selected: string[];
    onPick: (province: string) => void;
    disabled?: boolean;
}) {
    const id = useId();
    const {picker, setPicker} = useMapSelection();
    const latestPick = useRef(onPick);
    latestPick.current = onPick;
    const active = picker?.id === id;
    useEffect(() => {
        if (!active) return;
        setPicker(current => {
            if (current?.id !== id) return current;
            if (disabled) return null;
            if (current.title === title && current.eligible.join('|') === eligible.join('|') &&
                current.selected.join('|') === selected.join('|')) return current;
            return {...current, title, eligible, selected};
        });
    }, [active, disabled, eligible, id, selected, setPicker, title]);
    useEffect(() => () => setPicker(current => current?.id === id ? null : current), [id, setPicker]);
    return <button type="button" className="secondary map-picker" disabled={disabled || eligible.length === 0}
        aria-pressed={active} onClick={() => {
            setPicker(active ? null : {id, title, eligible, selected, pick: province => latestPick.current(province)});
            if (!active) document.getElementById('interactive-board')?.scrollIntoView({behavior: 'smooth', block: 'start'});
        }}>{active ? 'Stop choosing on map' : 'Choose on map'}</button>;
}
