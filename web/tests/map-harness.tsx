import {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import type {Snapshot} from '../src/api';
import {GameTable} from '../src/GameTable';
import {MapSelectionProvider} from '../src/map/MapSelection';
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
    return <MapSelectionProvider><div data-test-revision={snapshot.revision}>
        <GameTable snapshot={snapshot} busy={false} saved={false} submit={submit} connectionError="" actionError=""
            quotePurchase={async amount => {
                const quote = snapshot.game.tokenPurchaseOffers.find(offer => offer.amount === amount);
                if (!quote) throw new Error('No authoritative quote supplied for fixture amount.');
                return {revision: snapshot.revision, quote};
            }}/>
    </div></MapSelectionProvider>;
}
createRoot(document.getElementById('root')!).render(<Harness/>);
