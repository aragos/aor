import {StrictMode} from 'react';
import {createRoot} from 'react-dom/client';
import {CapitalLabel, LabelMessage} from '../src/IdentityLabel';
import {Emblem} from '../src/TablePanels';
import '../src/app.css';

const capitals = ['barcelona', 'genoa', 'venice', 'paris', 'london', 'hamburg'];

function Examples() {
    return <main style={{padding: '1rem'}}>
        {capitals.map(capital => <section key={capital}>
            <p data-alignment-case={`${capital} sentence`}>Before <CapitalLabel capital={capital}>{capital}</CapitalLabel> after.</p>
            <p data-alignment-case={`${capital} translation`}><LabelMessage
                text="Between {before} and {after}, then continue."
                values={{before: <CapitalLabel capital={capital}>{capital}</CapitalLabel>,
                    after: <CapitalLabel capital={capital}>{capital}</CapitalLabel>}}/></p>
            <h2 data-alignment-case={`${capital} heading`}>Inspect <CapitalLabel capital={capital}>{capital}</CapitalLabel> now</h2>
            <div className="decision-status" data-alignment-case={`${capital} waiting`}>
                <strong>Waiting for</strong><span className="waiting-players">
                    <CapitalLabel capital={capital}>{capital}</CapitalLabel>, <CapitalLabel capital={capital}>{capital}</CapitalLabel>
                </span>
            </div>
            <button type="button" data-alignment-case={`${capital} button`}><LabelMessage
                text="Before {name}"
                values={{name: <CapitalLabel capital={capital}>{capital}</CapitalLabel>}}/></button>
            <p className="small" data-alignment-case={`${capital} small`}>Claimants: <CapitalLabel capital={capital}>{capital}</CapitalLabel>, <CapitalLabel capital={capital}>{capital}</CapitalLabel></p>
            <p className="small" data-alignment-case={`${capital} small translation`}><LabelMessage
                text="Before {name}, then continue."
                values={{name: <CapitalLabel capital={capital}>{capital}</CapitalLabel>}}/></p>
            <p className="war-cession-summary" data-alignment-case={`${capital} war`}>
                <span>Winner:</span><CapitalLabel capital={capital}>{capital}</CapitalLabel><span>Capacity: 1</span>
            </p>
            <div className="event-misery-change small" data-alignment-case={`${capital} misery`}>
                <CapitalLabel capital={capital}>{capital}</CapitalLabel><strong>+1</strong> misery
            </div>
            <div className="event-position-change small" data-alignment-case={`${capital} position`}>
                <CapitalLabel capital={capital}>{capital}</CapitalLabel><span>Position 1 → 2</span>
            </div>
            <div className="player-identity" style={{maxWidth: '14rem'}} data-alignment-case={`${capital} player strip`}>
                <Emblem player={{capital}}/><span className="player-labels"><strong>{capital}</strong>
                    <span className="player-person">Ada</span></span><small>You</small>
            </div>
            <span className="order-track-player" data-alignment-case={`${capital} order reference`}>
                <CapitalLabel capital={capital}>{capital}</CapitalLabel><small>You</small>
            </span>
        </section>)}
        <p data-alignment-case="unassigned">Before <CapitalLabel capital={null}>A long public player handle</CapitalLabel> after.</p>
        <p style={{maxWidth: '14rem'}} data-alignment-case="wrapped translation"><LabelMessage
            text="A longer sentence before {name}, followed by more words and {other}."
            values={{name: <CapitalLabel capital="venice">Venice</CapitalLabel>,
                other: <CapitalLabel capital="paris">Paris</CapitalLabel>}}/></p>
    </main>;
}

createRoot(document.getElementById('root')!).render(<StrictMode><Examples/></StrictMode>);
