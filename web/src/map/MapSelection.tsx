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
    workspace: 'map' | 'markets' | 'advances';
    setWorkspace: Dispatch<SetStateAction<'map' | 'markets' | 'advances'>>;
    selected: string;
    setSelected: Dispatch<SetStateAction<string>>;
    selectedCard: string;
    setSelectedCard: Dispatch<SetStateAction<string>>;
    selectedAdvance: string;
    setSelectedAdvance: Dispatch<SetStateAction<string>>;
    picker: Picker | null;
    setPicker: Dispatch<SetStateAction<Picker | null>>;
}>({workspace: 'map', setWorkspace: () => undefined, selected: '', setSelected: () => undefined, selectedCard: '', setSelectedCard: () => undefined,
    selectedAdvance: '', setSelectedAdvance: () => undefined, picker: null, setPicker: () => undefined});

export function MapSelectionProvider({children}: {children: ReactNode}) {
    const [picker, setPicker] = useState<Picker | null>(null);
    const [workspace, setWorkspace] = useState<'map' | 'markets' | 'advances'>('map');
    const [selected, setSelected] = useState('');
    const [selectedCard, setSelectedCard] = useState('');
    const [selectedAdvance, setSelectedAdvance] = useState('');
    return <MapSelection.Provider value={{picker, setPicker, workspace, setWorkspace, selected, setSelected, selectedCard,
        setSelectedCard, selectedAdvance, setSelectedAdvance}}>{children}</MapSelection.Provider>;
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
    const {picker, setPicker, setWorkspace} = useMapSelection();
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
            if (!active) {
                setWorkspace('map');
                requestAnimationFrame(() => document.getElementById('interactive-board')?.scrollIntoView({behavior: 'smooth', block: 'start'}));
            }
        }}>{active ? 'Stop choosing on map' : 'Choose on map'}</button>;
}
