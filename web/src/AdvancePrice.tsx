import type {AdvanceOfferView} from './api';
import {label} from './labels';
import {t} from './messages';
import {LeaderCredit} from './LeaderCredit';

/** Displays the engine's quote without recomputing any credit or purchase total. */
export function AdvancePrice({offer, compact = false}: {
    offer: AdvanceOfferView | undefined; compact?: boolean;
}) {
    if (offer?.price === null || offer?.price === undefined) return <span>—</span>;
    const quote = offer.priceBreakdown;
    if (!quote) return <strong>${offer.price}</strong>;
    if (compact) return <span className="advance-price-compact">
        <strong>${quote.price}</strong>
        <small>{t('table.printedCost')}: ${quote.baseCost}</small>
        {quote.categoryCredit > 0 && <small>{t('table.categoryCredit')}: −${quote.categoryCredit}</small>}
        {quote.institutionalResearchCredit > 0 && <small>{t('table.institutionalResearchCredit')}: −${quote.institutionalResearchCredit}</small>}
        {quote.leaders.map(discount => <span className="advance-discount" key={discount.leaderCardId}>
            {label(discount.leaderCardId, 'leader')} <LeaderCredit amount={discount.amount} writtenRecordBonus={discount.writtenRecordBonus}/>
        </span>)}
    </span>;
    return <div className="advance-price">
        <dl className="detail-list price-breakdown">
            <div><dt>{t('table.printedCost')}</dt><dd>${quote.baseCost}</dd></div>
            {quote.categoryCredit > 0 && <div><dt>{t('table.categoryCredit')}</dt><dd>−${quote.categoryCredit}</dd></div>}
            {quote.institutionalResearchCredit > 0 && <div><dt>{t('table.institutionalResearchCredit')}</dt><dd>−${quote.institutionalResearchCredit}</dd></div>}
            {quote.leaders.map(discount => <div className="leader-discount" key={discount.leaderCardId}>
                <dt>{label(discount.leaderCardId, 'leader')}</dt>
                <dd><LeaderCredit amount={discount.amount} writtenRecordBonus={discount.writtenRecordBonus}/></dd>
            </div>)}
            <div><dt>{t('table.finalPrice')}</dt><dd><strong>${quote.price}</strong></dd></div>
        </dl>
        {quote.price === 0 && <p className="small muted">{t('table.minimumPrice')}</p>}
    </div>;
}
