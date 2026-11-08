import {useContext} from 'react';
import type {PublicCatalog} from './api';
import {ReferenceGameContext} from './HelpLink';
import {CapitalLabel} from './IdentityLabel';
import {ReferenceTrackPlayers} from './ReferenceTrackPlayers';
import {Commodity} from './TablePanels';
import {label} from './labels';
import {t} from './messages';

/** Printed card quantities and public live payout positions share the commodity's lookup. */
export function CommodityReference({catalog, topic, choices, onSelect}: {
    catalog: PublicCatalog; topic: string; choices?: readonly string[];
    onSelect: (commodity: string) => void;
}) {
    const current = useContext(ReferenceGameContext);
    const players = current?.players ?? [];
    const commodity = catalog.commodities.find(commodity => topic === `commodity-${commodity.id.toLowerCase()}`);
    if (!commodity) return null;
    const hasChoices = choices && choices.length > 1 && choices.includes(commodity.id);
    const counts = current?.commodityCards?.find(count => count.commodity === commodity.id)
        ?? catalog.commodityCards?.find(count => count.commodity === commodity.id);
    const owners = current?.commodityPayouts.find(payout => payout.commodity === commodity.id)?.owners ?? [];
    const boxFor = (player: typeof players[number]) => owners.find(owner => owner.player === player.id)?.effectiveBox ?? 0;
    const boxes = [...new Set([...commodity.printedPayouts.map((_, index) => index + 1),
        ...players.map(boxFor)])].sort((a, b) => a - b);
    return <section id="commodities">
        <h2>{t('reference.commodity-payout-boxes')}</h2>
        <p>{t('reference.the-effective-box-is-established-markets')}</p>
        {hasChoices && <nav aria-label={t('table.markets')} className="commodity-reference-choices">
            {choices.map(id => <button type="button" className="secondary" key={id}
                aria-pressed={id === commodity.id} onClick={() => onSelect(id)}><Commodity commodity={id}/></button>)}
        </nav>}
        <article id={`commodity-${commodity.id.toLowerCase()}`}>
            {!hasChoices && <h3><Commodity commodity={commodity.id}/></h3>}
            <p>{t('reference.base-value')}{commodity.baseValue}{t('reference.dice-total')}{commodity.diceTotal}</p>
            {counts && <>
                <dl className="commodity-card-counts" aria-label={t('reference.cardsByEpoch')}>
                    {counts.epochs.map((count, index) => <div key={index}>
                        <dt>{t('reference.epochCards', {epoch: index + 1})}</dt><dd>{count}</dd>
                    </div>)}
                </dl>
                {counts.unplayed !== null && <p className="commodity-circulation">{t('reference.unplayedCards', {count: counts.unplayed})}</p>}
                <p className="reference-note">{t('reference.cardCountExplanation')}</p>
            </>}
            {players.filter(player => player.advances.includes('INDUSTRY')).map(player => <p className="commodity-industry" key={player.id}>
                <CapitalLabel capital={player.capital}>{player.capital ? label(player.capital, 'province') : player.name}</CapitalLabel>
                {' · '}{t('commodity.industry')}
            </p>)}
            <ol className="payout-boxes" aria-label={t('reference.payoutBoxes', {commodity: label(commodity.id, 'commodity')})}>
                {boxes.map(box => {
                    const occupants = players.filter(player => boxFor(player) === box);
                    return <li className={`payout-box${occupants.length ? ' is-current' : ''}`} key={box} data-track-box={box}>
                        <span>{box}</span><strong>${commodity.baseValue * box * box}</strong>
                        <ReferenceTrackPlayers players={occupants}/>
                    </li>;
                })}
            </ol>
        </article>
    </section>;
}
