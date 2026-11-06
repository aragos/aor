import type {Snapshot} from './api';
import {PlayerLabel} from './IdentityLabel';
import {t} from './messages';

/** Live public usage follows the engine's turn reset and accepted history changes. */
export function CathedralUsage({snapshot}: {snapshot: Snapshot}) {
    const pairs = snapshot.game.cathedralPairs ?? [];
    const owners = [...new Set(pairs.map(pair => pair.owner))];
    const opponents = [...new Set(pairs.map(pair => pair.opponent))];
    return <section className="cathedral-usage" aria-label={t('cathedral.usage')}>
        <h3>{t('cathedral.usage')}</h3>
        <p className="small">{t('cathedral.usageHint')}</p>
        {pairs.length ? <div className="table-scroll"><table>
            <thead><tr><th scope="col">{t('cathedral.ownerAgainst')}</th>
                {opponents.map(id => <th scope="col" key={id}><PlayerLabel snapshot={snapshot} id={id}/></th>)}
            </tr></thead>
            <tbody>{owners.map(owner => <tr key={owner}><th scope="row"><PlayerLabel snapshot={snapshot} id={owner}/></th>
                {opponents.map(opponent => {
                    const pair = pairs.find(pair => pair.owner === owner && pair.opponent === opponent);
                    return <td key={opponent} data-cathedral-owner={owner} data-cathedral-opponent={opponent}
                        data-cathedral-used={pair?.used}>{t(pair?.used ? 'cathedral.used' : 'cathedral.unused')}</td>;
                })}
            </tr>)}</tbody>
        </table></div> : <p className="small">{t('cathedral.noPairs')}</p>}
    </section>;
}
