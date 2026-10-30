import type {MiseryChangeView, Snapshot} from './api';
import {t} from './messages';
import {PlayerLabel} from './IdentityLabel';
import {MiseryBox} from './MiseryBox';

/** A public consequence, using printed endpoints rather than treating spaces as box values. */
export function MiseryPreview({snapshot, changes, uncertainPlayers = []}: {
    snapshot: Snapshot; changes: MiseryChangeView[]; uncertainPlayers?: string[];
}) {
    if (!changes.length && !uncertainPlayers.length) return null;
    const track = snapshot.game.catalog?.miseryTrack;
    return <section className="misery-preview" aria-label={t('controls.miseryAfterAction')}>
        <p className="small">{t('controls.miseryAfterAction')}</p>
        <dl className="detail-list">{changes.map(change => <div key={change.player} data-misery-preview-player={change.player}>
            <dt><PlayerLabel snapshot={snapshot} id={change.player}/></dt><dd className="misery-transition">
                <MiseryBox value={change.fromValue} track={track}/> → <MiseryBox value={change.toValue} track={track}/>
            </dd>
        </div>)}{uncertainPlayers.map(id => {
            const player = snapshot.game.players.find(candidate => candidate.id === id);
            return player && <div key={id} data-misery-preview-player={id}><dt><PlayerLabel snapshot={snapshot} id={id}/></dt>
                <dd className="misery-transition"><MiseryBox value={player.misery} track={track}/> → ?</dd>
            </div>;
        })}</dl>
        {uncertainPlayers.length > 0 && <p className="small muted">{t('controls.warMiseryUncertain')}</p>}
    </section>;
}
