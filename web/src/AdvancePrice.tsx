import type {AdvanceOfferView} from './api';
import {advanceName, label} from './labels';
import {t} from './messages';
import {LeaderCredit} from './LeaderCredit';

/** Keep the current price prominent; named credit sources belong in optional detail. */
export function AdvancePrice({offer, printedCost, compact = false}: {
    offer: AdvanceOfferView | undefined; printedCost: number; compact?: boolean;
}) {
    const quote = offer?.priceBreakdown;
    const price = offer?.price ?? printedCost;
    const discount = quote?.effectiveDiscount ?? 0;
    if (compact) return <span className="advance-price-compact">
        <strong>${price}</strong>
        {discount > 0 && <small>{t('table.creditsApplied', {amount: discount})}</small>}
        {!quote && <small>{t('table.printedCost')}</small>}
    </span>;
    if (!quote) return <div className="advance-price"><strong className="price-total">${price}</strong>
        <span className="small muted"> {t('table.printedCost')}</span></div>;
    return <div className="advance-price">
        <strong className="price-total">${price}</strong>
        <details className="price-details">
            <summary>{discount > 0 ? t('table.creditsApplied', {amount: discount}) : t('table.priceDetails')}</summary>
            <dl className="detail-list price-breakdown">
                <div><dt>{t('table.printedCost')}</dt><dd>${quote.baseCost}</dd></div>
                {quote.advanceCredits.map(credit => <div className="advance-credit-source" data-credit-advance={credit.advance} key={credit.advance}>
                    <dt>{advanceName(credit.advance)}</dt><dd>−${credit.amount}</dd>
                </div>)}
                {quote.institutionalResearchCredit > 0 && <div><dt>{t('table.institutionalResearchCredit')}</dt><dd>−${quote.institutionalResearchCredit}</dd></div>}
                {quote.leaders.map(discount => <div className="leader-discount" key={discount.leaderCardId}>
                    <dt>{label(discount.leaderCardId, 'leader')}</dt>
                    <dd><LeaderCredit amount={discount.amount} writtenRecordBonus={discount.writtenRecordBonus}/></dd>
                </div>)}
                <div><dt>{t('table.finalPrice')}</dt><dd><strong>${price}</strong></dd></div>
            </dl>
            <p className="small muted">{t('table.creditTiming')}</p>
            {price === 0 && <p className="small muted">{t('table.minimumPrice')}</p>}
        </details>
    </div>;
}
