import type {AdvanceDefinition, AdvanceOfferView, Snapshot} from './api';
import {advanceName, label} from './labels';
import {t} from './messages';
import {PlayerLabels} from './IdentityLabel';
import {AdvanceBenefits} from './AdvanceBenefits';
import {AdvancePrice} from './AdvancePrice';
import {MiseryPreview} from './MiseryPreview';

/** Printed benefits and the viewer's current price remain visible without disclosures. */
export function AdvanceInspection({snapshot, advance, offer, close}: {
    snapshot: Snapshot; advance: AdvanceDefinition; offer?: AdvanceOfferView; close: () => void;
}) {
    const owners = snapshot.game.players.filter(player => player.advances.includes(advance.id));
    return <section className="object-decision advance-decision advance-inspection"
        aria-labelledby="advance-inspection-title">
        <header className="advance-inspection-header">
            <span className="advance-letter">{advance.letter}</span>
            <div><p className="eyebrow">{label(advance.category, 'category')}</p>
                <h2 id="advance-inspection-title">{advanceName(advance.id)}</h2></div>
            <button type="button" className="close-detail" aria-label={t('table.close')} onClick={close}>×</button>
        </header>
        <div className="advance-inspection-content">
            <div className="advance-description">
                <AdvanceBenefits id={advance.id} rules={snapshot.game.rules}/>
                <dl className="detail-list advance-facts">
                    <AdvanceCreditBenefit advance={advance}/>
                    <div className="advance-relief"><dt>{t('reference.reliefRating')}</dt>
                        <dd>{t('advance.reliefThisTurn', {amount: advance.miseryRelief})}</dd></div>
                    <div className="advance-prerequisites"><dt>{t('advance.prerequisites')}</dt>
                        <dd>{advance.prerequisites.map(advanceName).join(', ') || t('reference.none')}</dd></div>
                    <div className="advance-owners"><dt>{t('advance.owners')}</dt>
                        <dd><PlayerLabels snapshot={snapshot} ids={owners.map(player => player.id)}/></dd></div>
                </dl>
            </div>
            <div className="advance-inspection-price">
                <AdvancePrice offer={offer} printedCost={advance.cost} inlineDetails/>
                {offer?.unavailableReason && <p className="blocker">{t(`error.${offer.unavailableReason}`)}</p>}
                <MiseryPreview snapshot={snapshot} changes={offer?.miseryChanges ?? []}/>
            </div>
        </div>
    </section>;
}

/** Future category credit is distinct from the discounts applied to this purchase. */
export function AdvanceCreditBenefit({advance}: {advance: AdvanceDefinition}) {
    if (advance.credit === 0) return null;
    return <div className="advance-category-credit"><dt>{t('advance.creditGiven', {category: label(advance.category, 'category')})}</dt>
        <dd>+${advance.credit} <span className="small muted">{t('advance.otherAdvancesNextTurn')}</span></dd></div>;
}
