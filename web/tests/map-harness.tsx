import {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import type {Snapshot} from '../src/api';
import {BoardMap} from '../src/map/BoardMap';
import {MapSelectionProvider} from '../src/map/MapSelection';
import {GameActions} from '../src/GameActions';
import '../src/app.css';

declare global {
    interface Window {
        mapSnapshot?: Snapshot;
        setMapSnapshot?: (snapshot: Snapshot) => void;
        mapCommands: string[];
    }
}

window.mapCommands = [];
function Harness() {
    const [snapshot, setSnapshot] = useState(window.mapSnapshot);
    useEffect(() => {window.setMapSnapshot = setSnapshot;}, []);
    if (!snapshot) return <p>Supply a snapshot through the browser test driver.</p>;
    const submit = async (command: string) => {window.mapCommands.push(command); return true;};
    return <MapSelectionProvider><main className="game-shell" data-test-revision={snapshot.revision}>
        <BoardMap snapshot={snapshot} busy={false} submit={submit}/>
        <section className="decision panel"><GameActions key={snapshot.revision} snapshot={snapshot} busy={false} submit={submit}/></section>
    </main></MapSelectionProvider>;
}
createRoot(document.getElementById('root')!).render(<Harness/>);
