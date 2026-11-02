import {useContext} from 'react';
import type {PlayerView} from './api';
import {ReferenceGameContext} from './HelpLink';
import {CapitalLabel} from './IdentityLabel';
import {provinceName} from './labels';
import {t} from './messages';

/** Compact capital markers retain readable names for hover and assistive technology. */
export function ReferenceTrackPlayers({players}: {players: Pick<PlayerView, 'id' | 'name' | 'capital'>[]}) {
    const current = useContext(ReferenceGameContext);
    return players.length > 0 && <span className="reference-track-players">{players.map(player => {
        const viewer = player.id === current?.viewerId;
        const name = player.capital ? provinceName(player.capital) : player.name;
        const description = `${name}${viewer ? ` · ${t('table.you')}` : ''}`;
        return <span key={player.id} data-track-player={player.id} title={description}
            className={`reference-track-player${viewer ? ' is-viewer' : ''}`}>
            <CapitalLabel capital={player.capital}>
                <span className={player.capital ? 'sr-only' : ''}>{description}</span>
            </CapitalLabel>
        </span>;
    })}</span>;
}
