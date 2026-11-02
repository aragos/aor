import {useContext} from 'react';
import type {PublicCatalog} from './api';
import {ReferenceOrderContext} from './HelpLink';
import {CapitalLabel} from './IdentityLabel';
import {provinceName} from './labels';
import {t} from './messages';

/** Engine projections contain ascending, consecutive winning faces; an empty list cannot win. */
export function greenDieFaces(faces: number[]): string {
    if (!faces.length) return t('order.none');
    return faces.length === 1 ? String(faces[0]) : `${faces[0]}–${faces[faces.length - 1]}`;
}

export function TurnOrderReference({catalog}: {catalog: PublicCatalog}) {
    const current = useContext(ReferenceOrderContext);
    const resolved = !!current?.turnOrder.length;
    return <section id="turn-order">
        <h2>{t('reference.turnOrder')}</h2>
        {current && <><h3>{t(resolved ? 'order.current' : 'order.pending')}</h3>
            {!resolved && <p>{t('order.pendingExplanation')}</p>}</>}
        <table className="order-track">
            <caption>{t('order.track', {count: catalog.orderTrack.length})}</caption>
            <thead><tr><th scope="col">{t('order.positionHeading')}</th>
                <th scope="col">{t('order.boxHeading')}</th>
                <th scope="col">{t('order.greenHeading')}</th></tr></thead>
            <tbody>{catalog.orderTrack.map((box, index) => {
                const player = resolved ? current.players.find(player =>
                    current.orderBoxes[player.id] === box.number) : undefined;
                const viewer = !!player && player.id === current?.viewerId;
                return <tr key={box.number} data-order-box={box.number} className={viewer ? 'is-viewer' : ''}>
                    <th scope="row"><span>{index + 1}</span>{player && <span className="order-track-player">
                        <CapitalLabel capital={player.capital}>
                            {player.capital ? provinceName(player.capital) : player.name}
                        </CapitalLabel>{viewer && <small>{t('table.you')}</small>}
                    </span>}</th>
                    <td>{box.number}</td><td>{greenDieFaces(box.greenWins)}</td>
                </tr>;
            })}</tbody>
        </table>
        <p>{t('order.explanation')}</p><p>{t('order.proselytism')}</p>
        {resolved && <p>{t('order.changes')}</p>}
    </section>;
}
