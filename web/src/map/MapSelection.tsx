import {createContext, useCallback, useContext, useEffect, useId, useRef, useState} from 'react';
import type {Dispatch, ReactNode, SetStateAction} from 'react';
import {t} from '../messages';

interface Picker {
    id: string;
    kind?: 'province' | 'area';
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
    selectedArea: string;
    setSelectedArea: Dispatch<SetStateAction<string>>;
    selectedCard: string;
    setSelectedCard: Dispatch<SetStateAction<string>>;
    selectedAdvance: string;
    setSelectedAdvance: Dispatch<SetStateAction<string>>;
    picker: Picker | null;
    setPicker: Dispatch<SetStateAction<Picker | null>>;
    playerPicker: Picker | null;
    setPlayerPicker: Dispatch<SetStateAction<Picker | null>>;
    resetSelection: () => void;
    focusRevision: number;
}>({workspace: 'map', setWorkspace: () => undefined, selected: '', setSelected: () => undefined, selectedArea: '', setSelectedArea: () => undefined, selectedCard: '', setSelectedCard: () => undefined,
    selectedAdvance: '', setSelectedAdvance: () => undefined, picker: null, setPicker: () => undefined,
    playerPicker: null, setPlayerPicker: () => undefined, resetSelection: () => undefined, focusRevision: 0});

export function MapSelectionProvider({children}: {children: ReactNode}) {
    const [picker, setPicker] = useState<Picker | null>(null);
    const [playerPicker, setPlayerPicker] = useState<Picker | null>(null);
    const [workspace, setWorkspace] = useState<'map' | 'markets' | 'advances'>('map');
    const [selected, setSelected] = useState('');
    const [selectedArea, setSelectedArea] = useState('');
    const [selectedCard, setSelectedCard] = useState('');
    const [selectedAdvance, setSelectedAdvance] = useState('');
    const [focusRevision, setFocusRevision] = useState(0);
    const resetSelection = useCallback(() => {
        setSelected(''); setSelectedArea(''); setSelectedCard(''); setSelectedAdvance('');
        setPicker(null); setPlayerPicker(null); setFocusRevision(revision => revision + 1);
    }, []);
    useEffect(() => {
        if (selected || selectedCard || selectedAdvance) setSelectedArea('');
    }, [selected, selectedCard, selectedAdvance]);
    return <MapSelection.Provider value={{picker, setPicker, playerPicker, setPlayerPicker, workspace, setWorkspace, selected, setSelected, selectedArea, setSelectedArea, selectedCard,
        setSelectedCard, selectedAdvance, setSelectedAdvance, resetSelection, focusRevision}}>{children}</MapSelection.Provider>;
}

export const useMapSelection = () => useContext(MapSelection);

/** Map clicks only edit the requesting form. Its submit button sends the order. */
export function MapPicker({title, eligible, selected, onPick, disabled = false, autoActivate = false, kind = 'province'}: {
    title: string;
    eligible: string[];
    selected: string[];
    onPick: (province: string) => void;
    disabled?: boolean;
    autoActivate?: boolean;
    kind?: 'province' | 'area';
}) {
    const id = useId();
    const {picker, setPicker, setWorkspace} = useMapSelection();
    const latestPick = useRef(onPick);
    latestPick.current = onPick;
    const initialPicker = useRef({id, kind, title, eligible, selected, pick: (province: string) => latestPick.current(province)});
    initialPicker.current = {id, kind, title, eligible, selected, pick: province => latestPick.current(province)};
    const active = picker?.id === id;
    const activated = useRef(false);
    useEffect(() => {
        if (autoActivate && !disabled && !activated.current) {
            activated.current = true;
            setPicker(initialPicker.current);
            setWorkspace('map');
        }
    }, [autoActivate, disabled, kind, setPicker, setWorkspace]);
    useEffect(() => {
        if (!active) return;
        setPicker(current => {
            if (current?.id !== id) return current;
            if (current.kind === kind && current.title === title && current.eligible.join('|') === eligible.join('|') &&
                current.selected.join('|') === selected.join('|')) return current;
            return {...current, kind, title, eligible, selected};
        });
    }, [active, disabled, eligible, id, kind, selected, setPicker, title]);
    useEffect(() => () => setPicker(current => current?.id === id ? null : current), [id, setPicker]);
    return <button type="button" className="secondary map-picker" disabled={disabled || eligible.length === 0}
        aria-pressed={active} onClick={() => {
            setPicker(active ? null : {id, kind, title, eligible, selected, pick: province => latestPick.current(province)});
            if (!active) {
                setWorkspace('map');
                requestAnimationFrame(() => document.getElementById('interactive-board')?.scrollIntoView({behavior: 'smooth', block: 'start'}));
            }
        }}>{t(active ? 'board.stopChoosing' : 'board.chooseOnMap')}</button>;
}

/** Eligible player summaries edit the same card draft as its target buttons. */
export function PlayerPicker({eligible, selected, onPick, disabled}: {
    eligible: string[]; selected: string[]; onPick: (player: string) => void; disabled: boolean;
}) {
    const id = useId();
    const {setPlayerPicker} = useMapSelection();
    const latestPick = useRef(onPick);
    latestPick.current = onPick;
    const eligibleKey = eligible.join('|'), selectedKey = selected.join('|');
    useEffect(() => {
        if (!disabled) setPlayerPicker({id, title: '', eligible: eligibleKey.split('|').filter(Boolean),
            selected: selectedKey.split('|').filter(Boolean), pick: player => latestPick.current(player)});
        return () => setPlayerPicker(current => current?.id === id ? null : current);
    }, [disabled, eligibleKey, id, selectedKey, setPlayerPicker]);
    return null;
}
